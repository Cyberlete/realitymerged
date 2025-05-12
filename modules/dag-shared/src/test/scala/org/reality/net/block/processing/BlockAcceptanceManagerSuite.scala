package block.processing

import cats.data.{EitherT, NonEmptyList}
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.validated._
import org.reality.dag.block.BlockValidator.NotEnoughParents
import org.reality.net.domain.block.generators._
import org.reality.schema.BlockReference
import org.reality.schema.address.Address
import org.reality.schema.height.Height
import org.reality.security.hash.{Hash, ProofsHash}
import org.reality.security.signature.Signed
import eu.timepit.refined.auto._
import org.reality.dag.block
import org.reality.dag.block.BlockValidator
import org.reality.dag.block.processing.{
  BlockAcceptanceContext,
  BlockAcceptanceContextUpdate,
  BlockAcceptanceLogic,
  BlockAcceptanceManager,
  BlockAcceptanceResult,
  BlockNotAcceptedReason,
  ParentNotFound,
  SigningPeerBelowCollateral,
  UsageCount,
  ValidationFailed,
  initUsageCount
}
import org.reality.dag.domain.block.NETBlock
import org.reality.dag.transaction.TransactionChainValidator
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BlockAcceptanceManagerSuite extends SimpleIOSuite with Checkers {

  val validAcceptedParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "1"))
  val validRejectedParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "2"))
  val validAwaitingParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "3"))
  val validInitiallyAwaitingParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "4"))
  val invalidParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "5"))
  val commonAddress = Address("NET0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")

  def mkBlockAcceptanceManager(acceptInitiallyAwaiting: Boolean = true) =
    Ref[F].of[Map[Signed[NETBlock], Boolean]](Map.empty.withDefaultValue(false)).map { state =>
      val blockLogic = new BlockAcceptanceLogic[IO] {
        override def acceptBlock(
          block: Signed[NETBlock],
          txChains: Map[Address, TransactionChainValidator.TransactionNel],
          context: BlockAcceptanceContext[IO],
          contextUpdate: BlockAcceptanceContextUpdate
        ): EitherT[IO, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)] =
          EitherT(
            for {
              wasSeen <- state.getAndUpdate(wasSeen => wasSeen + ((block, true)))
            } yield
              block.parent.head match {
                case `validAcceptedParent` =>
                  (BlockAcceptanceContextUpdate.empty.copy(parentUsages = Map((block.parent.head, 1L))), initUsageCount)
                    .asRight[BlockNotAcceptedReason]

                case `validRejectedParent` =>
                  ParentNotFound(validRejectedParent)
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validAwaitingParent` =>
                  SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if !wasSeen.apply(block) =>
                  SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if wasSeen.apply(block) && acceptInitiallyAwaiting =>
                  (BlockAcceptanceContextUpdate.empty.copy(parentUsages = Map((block.parent.head, 1L))), initUsageCount)
                    .asRight[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if wasSeen.apply(block) && !acceptInitiallyAwaiting =>
                  ParentNotFound(validInitiallyAwaitingParent)
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case _ => ???
              }
          )
      }

      val blockValidator = new BlockValidator[IO] {

        override def validate(
          signedBlock: Signed[NETBlock],
          params: BlockValidator.BlockValidationParams
        ): IO[BlockValidator.BlockValidationErrorOr[
          (Signed[NETBlock], Map[Address, TransactionChainValidator.TransactionNel])
        ]] = signedBlock.parent.head match {
          case `invalidParent` => IO.pure(NotEnoughParents(0, 0).invalidNec)
          case _               => IO.pure((signedBlock, Map.empty[Address, TransactionChainValidator.TransactionNel]).validNec)

        }
      }
      BlockAcceptanceManager.make[IO](blockLogic, blockValidator)
    }

  test("accept valid block") {
    forall(validAcceptedNETBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty
          .copy(parentUsages = Map((validAcceptedParent, 1L))),
        blocks.sorted.map(b => (b, 0L)),
        Nil
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager()
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("reject valid block") {
    forall(validRejectedNETBlocksGen) {
      case (acceptedBlocks, rejectedBlocks) =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
          acceptedBlocks.sorted.map(b => (b, 0L)),
          rejectedBlocks.sorted.reverse.map(b => (b, ParentNotFound(validRejectedParent)))
        )

        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager()
          res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ rejectedBlocks, null)
        } yield expect.same(res, expected)
    }
  }

  test("awaiting valid block") {
    forall(validAwaitingNETBlocksGen) {
      case (acceptedBlocks, awaitingBlocks) =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
          acceptedBlocks.sorted.map(b => (b, 0L)),
          awaitingBlocks.sorted.reverse
            .map(b => (b, SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))))
        )
        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager()
          res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ awaitingBlocks, null)
        } yield expect.same(res, expected)
    }
  }

  test("accept initially awaiting valid block") {
    forall(validInitiallyAwaitingNETBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty
          .copy(parentUsages = Map((validInitiallyAwaitingParent, 1L))),
        blocks.sorted.map(b => (b, 0L)),
        Nil
      )

      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = true)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("reject initially awaiting valid block") {
    forall(validInitiallyAwaitingNETBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        Nil,
        blocks.sorted.reverse.map(b => (b, ParentNotFound(validInitiallyAwaitingParent)))
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = false)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("invalid block") {
    forall(invalidNETBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        Nil,
        blocks.sorted.reverse.map(b => (b, ValidationFailed(NonEmptyList.of(NotEnoughParents(0, 0)))))
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = false)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  def validAcceptedNETBlocksGen = netBlocksGen(1, validAcceptedParent)

  def validRejectedNETBlocksGen =
    for {
      rejected <- netBlocksGen(1, validRejectedParent)
      accepted <- netBlocksGen(0, validAcceptedParent)
    } yield (accepted, rejected)

  def validAwaitingNETBlocksGen =
    for {
      awaiting <- netBlocksGen(1, validAwaitingParent)
      accepted <- netBlocksGen(0, validAcceptedParent)
    } yield (accepted, awaiting)

  def validInitiallyAwaitingNETBlocksGen = netBlocksGen(1, validInitiallyAwaitingParent)

  def invalidNETBlocksGen = netBlocksGen(1, invalidParent)

  def netBlocksGen(minimalSize: Int, parent: BlockReference) =
    for {
      size <- Gen.choose(minimalSize, 3)
      blocks <- Gen.listOfN(size, signedNETBlockGen)
    } yield blocks.map(substituteParent(parent))

  def substituteParent(parent: BlockReference)(signedBlock: Signed[NETBlock]) =
    signedBlock.copy(value = signedBlock.value.copy(parent = NonEmptyList.of(parent)))
}
