package com.mogproject.mogami.core

import com.mogproject.mogami._
import com.mogproject.mogami.core.io._
import com.mogproject.mogami.util.MapUtil
import com.mogproject.mogami.util.Implicits._

import scala.util.Try


/**
  * State class
  */
case class State(turn: Player = BLACK, board: BoardType = Map.empty, hand: HandType = State.EMPTY_HANDS) extends CsaLike with SfenLike {

  require(checkCapacity, "the number of pieces must be within the capacity")
  require(hand.keySet == State.EMPTY_HANDS.keySet, "hand pieces must be in-hand type")
  require(board.forall { case (s, p) => s.isLegalZone(p) }, "all board pieces must be placed in their legal zones")
  require(!getKing(!turn).exists(getAttackBB(turn).get), "player must not be able to attack the opponent's king")

  import com.mogproject.mogami.core.State.MoveFrom
  import com.mogproject.mogami.core.State.PromotionFlag.{PromotionFlag, CannotPromote, CanPromote, MustPromote}

  override def toCsaString: String = {
    val boardString = (1 to 9).map { rank =>
      (9 to 1 by -1).map { file => board.get(Square(file, rank)).map(_.toCsaString).getOrElse(" * ") }.mkString(s"P$rank", "", "")
    }.mkString("\n")

    val handString = Player.constructor.map { p =>
      s"P${p.toCsaString}" + Ptype.inHand.map { pt => s"00${pt.toCsaString}" * hand.getOrElse(Hand(p, pt), 0) }.mkString
    }.mkString("\n")

    Seq(boardString, handString, turn.toCsaString).mkString("\n")
  }

  override def toSfenString: String = {
    def stringifyNumber(n: Int, threshold: Int = 0): String = (n <= threshold).fold("", n.toString)

    val boardString = (1 to 9).map { rank =>
      val (ss, nn) = (9 to 1 by -1).map { file =>
        board.get(Square(file, rank))
      }.foldLeft(("", 0)) {
        case ((s, n), Some(p)) => (s + stringifyNumber(n) + p.toSfenString, 0)
        case ((s, n), None) => (s, n + 1)
      }
      ss + stringifyNumber(nn)
    }.mkString("/")

    val handString = hand.filter(_._2 != 0).toSeq.sorted.map { case (p, n) => stringifyNumber(n, 1) + p.toPiece.toSfenString }.mkString

    s"$boardString ${turn.toSfenString} ${handString.isEmpty.fold("-", handString)}"
  }

  def updateBoardPiece(square: Square, piece: Piece): Option[State] = Try(copy(board = board.updated(square, piece))).toOption

  def updateHandPiece(piece: Piece, num: Int): Option[State] = Try(copy(hand = hand.updated(Hand(piece), num))).toOption

  /**
    * Occupancy bitboards
    */
  private[this] def aggregateSquares(boardMap: BoardType): BitBoard = boardMap.keys.view.map(BitBoard.ident).fold(BitBoard.empty)(_ | _)

  private[this] lazy val occupancyAll: BitBoard = aggregateSquares(board)

  private[this] lazy val occupancyByOwner: Map[Player, BitBoard] = board.groupBy(_._2.owner).mapValues(aggregateSquares)

  private[this] lazy val occupancyByPiece: Map[Piece, BitBoard] = board.groupBy(_._2).mapValues(aggregateSquares)

  def occupancy: BitBoard = occupancyAll

  def occupancy(player: Player): BitBoard = occupancyByOwner.getOrElse(player, BitBoard.empty)

  def occupancy(piece: Piece): BitBoard = occupancyByPiece.getOrElse(piece, BitBoard.empty)

  def getSquares(piece: Piece): Set[Square] = occupancy(piece).toSet

  def getKing(player: Player): Option[Square] = occupancy(Piece(player, KING)).toList.headOption

  lazy val turnsKing: Option[Square] = getKing(turn)

  def getRangedPieces(player: Player): Seq[(Square, Piece)] = board.filter { case (_, p) => p.owner == player && p.isRanged }.toSeq

  /**
    * Attack bitboards
    */
  lazy val attackBBOnBoard: Map[Player, Map[Square, BitBoard]] = {
    val m = (for ((sq, piece@Piece(owner, _)) <- board) yield {
      (owner, sq) -> Attack.get(piece, Some(sq), occupancy, occupancy(Piece(owner, PAWN)))
    }).filter(_._2.nonEmpty).groupBy(_._1._1).mapValues(_.map { case ((_, s), b) => s -> b })

    Map(BLACK -> Map.empty[Square, BitBoard], WHITE -> Map.empty[Square, BitBoard]) ++ m
  }

  lazy val attackBBInHand: Map[Hand, BitBoard] = for {
    (h, num) <- hand if h.owner == turn && num > 0
  } yield {
    h -> Attack.get(h.toPiece, None, occupancy, occupancy(Piece(turn, PAWN)))
  }

  def getAttackBB(player: Player): BitBoard = attackBBOnBoard(player).values.fold(BitBoard.empty)(_ | _)

  /**
    * Get the positions of pieces that are attacking the turn player's king
    *
    * @return set of squares
    */
  lazy val attackers: Set[Square] = turnsKing.map(k => attackBBOnBoard(!turn).filter(_._2.get(k)).keys.toSet).getOrElse(Set.empty)

  /**
    * Get the attackers' potential attack bitboard (assuming that there is no obstacles)
    */
  lazy val attackerPotentialBB: BitBoard = attackers.map(sq => Attack.get(board(sq), Some(sq), BitBoard.empty, BitBoard.empty)).fold(BitBoard.empty)(_ | _)

  /**
    * Get the guard pieces, which protect the turn player's king from ranged attack.
    *
    * @return set of squares and guarding area bitboards
    */
  lazy val guards: Map[Square, BitBoard] = {
    for {
      (s, p) <- board if p.owner == !turn && p.isRanged
      k <- getKing(turn)
      bt = s.getBetweenBB(k) if Attack.getRangedAttack(p, s, BitBoard.empty).get(k)
      g = bt & occupancy if g.count == 1
    } yield {
      g.toList.head -> bt
    }
  }

  /**
    * Check if the player is checked.
    */
  lazy val isChecked: Boolean = turnsKing.exists(getAttackBB(!turn).get)

  def getNonSuicidalMovesOnBoard: Map[Square, BitBoard] = for ((sq, bb) <- attackBBOnBoard(turn)) yield {
    if (board(sq).ptype == KING)
      sq -> (bb & ~getAttackBB(!turn))
    else if (guards.keySet.contains(sq))
      sq -> (bb & guards(sq))
    else
      sq -> bb
  }

  def getEscapeMoves: Map[MoveFrom, BitBoard] = {
    require(turnsKing.isDefined)

    // king's move
    val king = turnsKing.get
    val kingEscape = Map(king -> (attackBBOnBoard(turn)(king) & ~(getAttackBB(!turn) | occupancy(turn) | attackerPotentialBB)))

    // move a piece between king and the attacker or capture the attacker (except king's move)
    val attacker = if (attackers.size == 1) attackers.headOption else None
    val between = attacker.map(king.getBetweenBB)
    val betweenAndAttacker = attacker.map(atk => between.get.set(atk))

    val moveBetween = for {
      (sq, bb) <- getNonSuicidalMovesOnBoard if sq != king
      bt <- betweenAndAttacker
    } yield sq -> (bb & bt)

    // drop a piece between king and the attacker
    val dropBetween = for ((sq, bb) <- attackBBInHand; bt <- between) yield sq -> (bb & bt)

    (kingEscape ++ moveBetween).map { case (k, v) => Left(k) -> v } ++ dropBetween.map { case (k, v) => Right(k) -> v }
  }

  /**
    * All legal moves in the bitboard description
    *
    * @return map of the square from MoveFrom and attack bitboard
    */
  lazy val legalMovesBB: Map[MoveFrom, BitBoard] = {
    val m = if (isChecked)
      getEscapeMoves
    else
      getNonSuicidalMovesOnBoard.map { case (k, v) => Left(k) -> v } ++ attackBBInHand.map { case (k, v) => Right(k) -> v }
    m.mapValues(_ & ~occupancy(turn)).filter(_._2.nonEmpty)
  }

  // todo: consider converting to Set
  def legalMoves: Seq[Move] = (
    for {
      (from, bb) <- legalMovesBB
      to <- bb.toList
      promote <- getPromotionList(from, to)
      mv <- from.fold(MoveBuilderSfenBoard(_, to, promote), p => MoveBuilderSfenHand(p.ptype, to)).toMove(this)
    } yield mv).toSeq

  /**
    * Check if the move is legal.
    *
    * @param move move to test
    * @return true if the move is legal
    */
  def isValidMove(move: Move): Boolean = legalMoves.contains(move.copy(elapsedTime = None))

  /** *
    * Check if the state is mated.
    *
    * @return true if mated
    */
  def isMated: Boolean = legalMovesBB.isEmpty

  /**
    * Make one move.
    *
    * @param move move to make
    * @return new state
    */
  def makeMove(move: Move): Option[State] = isValidMove(move).option {
    val releaseBoard: BoardType => BoardType = move.from.when(sq => b => b - sq)
    val releaseHand: HandType => HandType = move.isDrop.when(MapUtil.decrementMap(_, Hand(move.newPiece)))
    val obtainHand: HandType => HandType = move.capturedPiece.when(p => h => MapUtil.incrementMap(h, Hand(!p.demoted)))
    State(!turn, releaseBoard(board) + (move.to -> move.newPiece), (releaseHand andThen obtainHand) (hand))
  }

  def getPieceCount: Map[Piece, Int] = MapUtil.mergeMaps(board.groupBy(_._2).mapValues(_.size), hand.map { case (k, v) => k.toPiece -> v })(_ + _, 0)

  def getUsedPtypeCount: Map[Ptype, Int] = getPieceCount.groupBy(_._1.ptype.demoted).mapValues(_.values.sum)

  def getUnusedPtypeCount: Map[Ptype, Int] = MapUtil.mergeMaps(State.capacity, getUsedPtypeCount)(_ - _, 0)

  def checkCapacity: Boolean = getPieceCount.filterKeys(_.ptype == KING).forall(_._2 <= 1) && getUnusedPtypeCount.values.forall(_ >= 0)

  def canAttack(from: Square, to: Square): Boolean = canAttack(Left(from), to)

  def canAttack(from: MoveFrom, to: Square): Boolean = legalMovesBB.get(from).exists(_.get(to))

  /**
    * @note This method does not check the capability of the piece
    */
  def getPromotionFlag(from: MoveFrom, to: Square): Option[PromotionFlag] = {
    from match {
      case Left(fr) =>
        for (p <- board.get(fr) if p.owner == turn) yield {
          (p.ptype.canPromote, fr.isPromotionZone(turn) || to.isPromotionZone(turn), to.isLegalZone(p)) match {
            case (true, true, true) => CanPromote
            case (true, true, false) => MustPromote
            case _ => CannotPromote
          }
        }
      case Right(_) => Some(CannotPromote)
    }
  }

  private[this] def getPromotionList(from: MoveFrom, to: Square): List[Boolean] = getPromotionFlag(from, to) match {
    case Some(CanPromote) => List(false, true)
    case Some(MustPromote) => List(true)
    case Some(CannotPromote) => List(false)
    case None => List()
  }

  /**
    * Check if the in-hand piece is non-empty.
    */
  def hasHand(h: Hand): Boolean = hand.get(h).exists(_ > 0)
}

object State extends CsaStateReader with SfenStateReader {

  type BoardType = Map[Square, Piece]
  type HandType = Map[Hand, Int]

  // board or hand
  type MoveFrom = Either[Square, Hand]

  object PromotionFlag {

    sealed trait PromotionFlag

    case object CannotPromote extends PromotionFlag

    case object CanPromote extends PromotionFlag

    case object MustPromote extends PromotionFlag

  }

  val EMPTY_HANDS: HandType = (for (t <- Player.constructor; pt <- Ptype.inHand) yield Hand(t, pt) -> 0).toMap

  val empty = State(BLACK, Map.empty, EMPTY_HANDS)
  lazy val capacity: Map[Ptype, Int] = Map(PAWN -> 18, LANCE -> 4, KNIGHT -> 4, SILVER -> 4, GOLD -> 4, BISHOP -> 2, ROOK -> 2, KING -> 2)

  /**
    * Get the square where the turn-to-move player's king.
    *
    * @return None if the king is not on board
    */
  def getKingSquare(player: Player, board: BoardType): Option[Square] =
    board.view.filter { case (s, p) => p == Piece(player, KING) }.map(_._1).headOption

  // constant states
  val HIRATE = State(BLACK, Map(
    Square(1, 1) -> Piece(WHITE, LANCE),
    Square(2, 1) -> Piece(WHITE, KNIGHT),
    Square(3, 1) -> Piece(WHITE, SILVER),
    Square(4, 1) -> Piece(WHITE, GOLD),
    Square(5, 1) -> Piece(WHITE, KING),
    Square(6, 1) -> Piece(WHITE, GOLD),
    Square(7, 1) -> Piece(WHITE, SILVER),
    Square(8, 1) -> Piece(WHITE, KNIGHT),
    Square(9, 1) -> Piece(WHITE, LANCE),
    Square(2, 2) -> Piece(WHITE, BISHOP),
    Square(8, 2) -> Piece(WHITE, ROOK),
    Square(1, 3) -> Piece(WHITE, PAWN),
    Square(2, 3) -> Piece(WHITE, PAWN),
    Square(3, 3) -> Piece(WHITE, PAWN),
    Square(4, 3) -> Piece(WHITE, PAWN),
    Square(5, 3) -> Piece(WHITE, PAWN),
    Square(6, 3) -> Piece(WHITE, PAWN),
    Square(7, 3) -> Piece(WHITE, PAWN),
    Square(8, 3) -> Piece(WHITE, PAWN),
    Square(9, 3) -> Piece(WHITE, PAWN),
    Square(1, 7) -> Piece(BLACK, PAWN),
    Square(2, 7) -> Piece(BLACK, PAWN),
    Square(3, 7) -> Piece(BLACK, PAWN),
    Square(4, 7) -> Piece(BLACK, PAWN),
    Square(5, 7) -> Piece(BLACK, PAWN),
    Square(6, 7) -> Piece(BLACK, PAWN),
    Square(7, 7) -> Piece(BLACK, PAWN),
    Square(8, 7) -> Piece(BLACK, PAWN),
    Square(9, 7) -> Piece(BLACK, PAWN),
    Square(2, 8) -> Piece(BLACK, ROOK),
    Square(8, 8) -> Piece(BLACK, BISHOP),
    Square(1, 9) -> Piece(BLACK, LANCE),
    Square(2, 9) -> Piece(BLACK, KNIGHT),
    Square(3, 9) -> Piece(BLACK, SILVER),
    Square(4, 9) -> Piece(BLACK, GOLD),
    Square(5, 9) -> Piece(BLACK, KING),
    Square(6, 9) -> Piece(BLACK, GOLD),
    Square(7, 9) -> Piece(BLACK, SILVER),
    Square(8, 9) -> Piece(BLACK, KNIGHT),
    Square(9, 9) -> Piece(BLACK, LANCE)
  ), EMPTY_HANDS)

  val MATING_BLACK = State(BLACK, Map(
    Square(5, 1) -> Piece(WHITE, KING)
  ), EMPTY_HANDS ++ Map(
    Hand(BLACK, ROOK) -> 2,
    Hand(BLACK, BISHOP) -> 2,
    Hand(BLACK, GOLD) -> 4,
    Hand(BLACK, SILVER) -> 4,
    Hand(BLACK, KNIGHT) -> 4,
    Hand(BLACK, LANCE) -> 4,
    Hand(BLACK, PAWN) -> 18
  ))

  val MATING_WHITE = State(WHITE, Map(
    Square(5, 9) -> Piece(BLACK, KING)
  ), EMPTY_HANDS ++ Map(
    Hand(WHITE, ROOK) -> 2,
    Hand(WHITE, BISHOP) -> 2,
    Hand(WHITE, GOLD) -> 4,
    Hand(WHITE, SILVER) -> 4,
    Hand(WHITE, KNIGHT) -> 4,
    Hand(WHITE, LANCE) -> 4,
    Hand(WHITE, PAWN) -> 18
  ))
}