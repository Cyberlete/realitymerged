package org.reality.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}
import org.reality.dag.block.processing.{BlockAcceptanceLogic, BlockAcceptanceManager}
import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.ext.cats.effect.ResourceIO
import org.reality.ext.cats.syntax.next._
import org.reality.infrastructure.snapshot._
import org.reality.keytool.KeyPairGenerator
import org.reality.schema._
import org.reality.schema.address.Address
import org.reality.schema.balance.{Amount, Balance}
import org.reality.schema.height.{Height, SubHeight}
import org.reality.schema.peer.PeerId
import org.reality.schema.transaction.{Transaction, TransactionReference}
import org.reality.sdk.domain.statechannel.StateChannelValidator
import org.reality.sdk.infrastructure.consensus.SelectActivePeers
import org.reality.sdk.infrastructure.metrics.Metrics
import org.reality.sdk.infrastructure.snapshot._
import org.reality.security.hash.{Hash, ProofsHash}
import org.reality.security.key.ops.PublicKeyOps
import org.reality.security.signature.{Signed, SignedValidator}
import org.reality.security.{Hashed, SecurityProvider}
import org.reality.syntax.sortedCollection._
import org.reality.tools.{AddressParams, NETBlockGenerator, TransactionGenerator}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.reality.dag.block.BlockValidator
import org.reality.dag.domain.block.NETBlockAsActiveTip
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import org.reality.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object GlobalSnapshotTraverseSuite extends MutableIOSuite with Checkers {
  type GenKeyPairFn = () => KeyPair

  type Res = (SecurityProvider[IO], Metrics[IO], Random[IO])

  override def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    metrics <- Metrics.forAsync[IO](Seq.empty)
    random <- Random.scalaUtilRandom[IO].asResource
  } yield (sp, metrics, random)

  val balances: Map[Address, Balance] = Map(Address("NET8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC") -> Balance(NonNegLong(10L)))

  def mkSnapshots(nets: List[List[NETBlockAsActiveTip]], initBalances: Map[Address, Balance])(
    implicit S: SecurityProvider[IO]
  ): IO[((Hashed[GlobalSnapshot], GlobalSnapshotInfo), NonEmptyList[Hashed[GlobalSnapshot]], PeerId)] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val peerId = PeerId.fromPublic(keyPair.getPublic)
      GlobalSnapshot
        .mkGenesis(initBalances, EpochProgress.MinValue, peerId)
        .flatMap {
          case (genesis, state) =>
            Signed
              .forAsyncJson[IO, GlobalSnapshot](genesis, keyPair)
              .flatMap(_.toHashed)
              .flatMap { genesis =>
                nets
                  .foldLeftM(NonEmptyList.of[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)]((genesis, state))) {
                    case (snapshots, blocksChunk) =>
                      mkSnapshot(
                        snapshots.head._1.hash,
                        snapshots.head._1.signed.value,
                        snapshots.head._2,
                        keyPair,
                        blocksChunk.toSortedSet
                      )
                        .map(snapshots.prepend)
                  }
                  .map(incrementals => ((genesis, state), incrementals.reverse.map(_._1)))
              }
        }
        .map { case (a, b) => (a, b, peerId) }
    }

  def mkSnapshot(
    lastHash: Hash,
    lastSnapshot: GlobalSnapshot,
    lastInfo: GlobalSnapshotInfo,
    keyPair: KeyPair,
    blocks: SortedSet[NETBlockAsActiveTip]
  )(
    implicit S: SecurityProvider[IO]
  ): IO[(Hashed[GlobalSnapshot], GlobalSnapshotInfo)] =
    for {
      activeTips <- lastSnapshot.activeTips
      currentOrdinal = lastSnapshot.ordinal.next
      txs = blocks.flatMap(_.block.value.transactions.toSortedSet)
      lastTxRefs <- txs
        .groupBy(_.source)
        .toList
        .flatMap { case (address, ts) => ts.maxByOption(_.ordinal.value.value).map(address -> _) }
        .traverse { case (address, t) => TransactionReference.of(t).map(address -> _) }
        .map(SortedMap.from(_))
        .map(lastInfo.lastTxRefs ++ _)
      balances = SortedMap.from(
        txs
          .map(_.value)
          .foldLeft(lastInfo.balances.view.mapValues(_.value.value).toMap) {
            case (aggBalances, tx) =>
              val srcBalance = aggBalances.getOrElse(tx.source, 0L) - (tx.amount.value.value + tx.fee.value.value)
              val dstBalance = aggBalances.getOrElse(tx.destination, 0L) + tx.amount.value.value

              aggBalances ++ Map(tx.source -> srcBalance, tx.destination -> dstBalance)
          }
          .view
          .mapValues(l => Balance(NonNegLong.unsafeFrom(l)))
      )
      nextRotationFacilitators = {
        val selectionInterval = SelectActivePeers.selectionInterval
        if (currentOrdinal.value % selectionInterval == 0L) {
          (currentOrdinal.nextN(selectionInterval), lastInfo.nextRotationFacilitators._2)
        } else { lastInfo.nextRotationFacilitators }
      }

      newSnapshotInfo = lastInfo.copy(
        lastTxRefs = lastTxRefs,
        balances = balances,
        nextRotationFacilitators = nextRotationFacilitators
      )

      newSnapshotInfoStateProof <- newSnapshotInfo.stateProof
      snapshot = GlobalSnapshot(
        currentOrdinal,
        Height.MinValue,
        SubHeight.MinValue,
        lastHash,
        blocks.toSortedSet,
        SortedMap.empty,
        SortedSet.empty,
        lastSnapshot.epochProgress,
        SortedSet.empty,
        SortedSet.empty,
        lastSnapshot.tips.copy(remainedActive = activeTips),
        newSnapshotInfoStateProof
      )
      signed <- Signed.forAsyncJson[IO, GlobalSnapshot](snapshot, keyPair)
      hashed <- signed.toHashed
    } yield (hashed, newSnapshotInfo)

  type NETS = (List[Address], Long, SortedMap[Address, Signed[Transaction]], List[List[NETBlockAsActiveTip]])

  def mkBlocks(feeValue: NonNegLong, numberOfAddresses: Int, txnsChunksRanges: List[(Int, Int)], blocksChunksRanges: List[(Int, Int)])(
    implicit S: SecurityProvider[IO],
    R: Random[IO]
  ): IO[NETS] = for {
    keyPairs <- (1 to numberOfAddresses).toList.traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    addressParams = keyPairs.map(keyPair => AddressParams(keyPair))
    addresses = keyPairs.map(_.getPublic.toAddress)
    txnsSize = if (txnsChunksRanges.nonEmpty) txnsChunksRanges.map(_._2).max.toLong else 0
    txns <- TransactionGenerator
      .infiniteTransactionStream[IO](PosInt.unsafeFrom(1), feeValue, NonEmptyList.fromListUnsafe(addressParams))
      .take(txnsSize)
      .compile
      .toList
    lastTxns = txns.groupBy(_.source).view.mapValues(_.last).toMap.toSortedMap
    transactionsChain = txnsChunksRanges
      .foldLeft[List[List[Signed[Transaction]]]](Nil) { case (acc, (start, end)) => txns.slice(start, end) :: acc }
      .map(txns => NonEmptySet.fromSetUnsafe(SortedSet.from(txns)))
      .reverse
    blockSigningKeyPairs <- NonEmptyList.of("", "", "").traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    nets <- NETBlockGenerator.createNETs(transactionsChain, initialReferences(), blockSigningKeyPairs).compile.toList
    chaunkedNets = blocksChunksRanges
      .foldLeft[List[List[NETBlockAsActiveTip]]](Nil) { case (acc, (start, end)) => nets.slice(start, end) :: acc }
      .reverse
  } yield (addresses, txnsSize, lastTxns, chaunkedNets)

  def gst(
    genesis: Hashed[GlobalSnapshot],
    genesisState: GlobalSnapshotInfo,
    incrementalSnapshots: List[Hashed[GlobalSnapshot]],
    rollbackHash: Hash
  )(implicit S: SecurityProvider[IO]) = {
    def loadGenesis(hash: Hash): IO[Option[GlobalSnapshotInfo]] =
      hash match {
        case h if h === genesis.lastSnapshotHash => Some(genesisState).pure[IO]
        case _                                   => None.pure[IO]
      }
    def loadGlobalIncrementalSnapshot(hash: Hash): IO[Option[Signed[GlobalSnapshot]]] =
      hash match {
        case h if h =!= genesis.lastSnapshotHash =>
          incrementalSnapshots.map(snapshot => (snapshot.hash, snapshot)).toMap.get(hash).map(_.signed).pure[IO]
        case _ => None.pure[IO]
      }

    val signedValidator = SignedValidator.make[IO]
    val blockValidator =
      BlockValidator.make[IO](
        signedValidator,
        TransactionChainValidator.make[IO],
        TransactionValidator.make[IO](signedValidator)
      )
    val blockAcceptanceManager = BlockAcceptanceManager.make(BlockAcceptanceLogic.make[IO], blockValidator)
    val stateChannelValidator = StateChannelValidator.make[IO](signedValidator)

    val stateChannelProcessor = GlobalSnapshotStateChannelEventsProcessor.make[IO](stateChannelValidator)
    val snapshotAcceptanceManager = GlobalSnapshotAcceptanceFunctions.make[IO](blockAcceptanceManager, stateChannelProcessor, Amount.empty)
    val snapshotContextFunctions = GlobalSnapshotContextFunctions.make[IO](snapshotAcceptanceManager)
    GlobalSnapshotTraverse.make[IO](loadGlobalIncrementalSnapshot, loadGenesis, snapshotContextFunctions, rollbackHash)
  }

  test("can compute state for given incremental global snapshot") { res =>
    implicit val (sp, _, _) = res

    for {
      ((first, state), incrementals, peerId) <- mkSnapshots(List.empty, balances)
      traverser = gst(first, state, incrementals.toList, incrementals.head.hash)
      state <- traverser.loadChain()
    } yield
      expect.eql(
        GlobalSnapshotInfo(
          SortedMap.empty,
          SortedMap.empty,
          SortedMap.from(balances),
          SortedMap.empty,
          SortedMap.empty,
          SortedSet(peerId),
          (SnapshotOrdinal(SelectActivePeers.selectionInterval), NonEmptySet.one(peerId))
        ),
        state._1
      )
  }

  test("computed state contains last refs and preserve total amount of balances when no fees or rewards ") { res =>
    implicit val (sp, _, random) = res

    forall(netBlockChainGen()) { output: IO[NETS] =>
      for {
        (addresses, _, lastTxns, chunkedNets) <- output
        ((first, state), incrementals, peerId) <- mkSnapshots(
          chunkedNets,
          addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
        )
        traverser = gst(first, state, incrementals.toList, incrementals.last.hash)
        (info, _) <- traverser.loadChain()
        totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
        lastTxRefs <- lastTxns.traverse(TransactionReference.of(_))
      } yield expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L))), (lastTxRefs, totalBalance))

    }
  }

  test("computed state contains last refs and include fees in total amount of balances") { res =>
    implicit val (sp, _, random) = res

    forall(netBlockChainGen(1L)) { output: IO[NETS] =>
      for {
        (addresses, txnsSize, lastTxns, chunkedNets) <- output
        ((first, state), incrementals, peerId) <- mkSnapshots(
          chunkedNets,
          addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
        )
        traverser = gst(first, state, incrementals.toList, incrementals.last.hash)
        (info, _) <- traverser.loadChain()
        totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
        lastTxRefs <- lastTxns.traverse(TransactionReference.of(_))
      } yield
        expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L - txnsSize * 1L))), (lastTxRefs, totalBalance))

    }
  }

  private def initialReferences() =
    NonEmptyList.fromListUnsafe(
      List
        .range(0, 4)
        .map { i =>
          BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i)))
        }
    )

  private def netBlockChainGen(
    feeValue: NonNegLong = 0L
  )(implicit r: Random[IO], sc: SecurityProvider[IO]): Gen[IO[NETS]] = for {
    numberOfAddresses <- Gen.choose(2, 5)
    txnsChunksRanges <- Gen
      .listOf(Gen.choose(0, 50))
      .map(l => (0 :: l).distinct.sorted)
      .map(list => list.zip(list.tail))
    blocksChunksRanges <- Gen
      .const((0 to txnsChunksRanges.size).toList)
      .map(l => (0 :: l).distinct.sorted)
      .map(list => list.zip(list.tail))
  } yield mkBlocks(feeValue, numberOfAddresses, txnsChunksRanges, blocksChunksRanges)

}
