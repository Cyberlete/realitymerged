package org.reality.infrastructure.snapshot

import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.reality.dag.snapshot.epoch.EpochProgress
import org.reality.ext.cats.syntax.next._
import org.reality.ext.crypto._
import org.reality.keytool.KeyPairGenerator
import org.reality.schema.height.{Height, SubHeight}
import org.reality.schema.peer.PeerId
import org.reality.schema.{address, balance}
import org.reality.security.SecurityProvider
import org.reality.security.signature.Signed

import better.files._
import eu.timepit.refined.auto._
import fs2.io.file.Path
import org.reality.dag.snapshot.{GlobalSnapshot, GlobalSnapshotInfo}
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotStorageSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotStorageSuite.Res] =
    Supervisor[IO].flatMap { supervisor =>
      SecurityProvider.forAsync[IO].map((supervisor, _))
    }

  def mkStorage(tmpDir: File)(implicit S: Supervisor[IO]) = {
    val snapPath = Path(tmpDir.pathAsString)
    val infoPath = snapPath / "state"
    (GlobalSnapshotLocalFileSystemStorage.make[IO](snapPath), GlobalSnapshotInfoLocalFileSystemStorage.make[IO](infoPath)).flatMapN {
      case (snapStorage, infoStorage) =>
        GlobalSnapshotStorage.make[IO](snapStorage, infoStorage, 5L)
    }
  }

  def mkSnapshots(
    implicit S: SecurityProvider[IO]
  ): IO[((Signed[GlobalSnapshot], GlobalSnapshotInfo), (Signed[GlobalSnapshot], GlobalSnapshotInfo))] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue, PeerId.fromPublic(keyPair.getPublic)).flatMap {
        case (genesis, state) =>
          Signed.forAsyncJson[IO, GlobalSnapshot](genesis, keyPair).flatMap { genesis =>
            def snapshot =
              GlobalSnapshot(
                genesis.value.ordinal.next,
                Height.MinValue,
                SubHeight.MinValue,
                genesis.value.hash.toOption.get,
                SortedSet.empty,
                SortedMap.empty,
                SortedSet.empty,
                genesis.value.epochProgress,
                SortedSet.empty,
                SortedSet.empty,
                genesis.tips,
                genesis.stateProof
              )

            Signed.forAsyncJson[IO, GlobalSnapshot](snapshot, keyPair).map(snapshot => ((genesis, state), (snapshot, state)))
          }
      }
    }

  test("head - returns none for empty storage") { res =>
    implicit val (s, _) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        storage.head.map {
          expect.same(_, none)
        }
      }
    }
  }

  test("head - returns latest snapshot if not empty") { res =>
    implicit val (s, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case ((genesis, genesisState), (snapshot, currentState)) =>
            storage.prepend(genesis, genesisState) >>
              storage.prepend(snapshot, currentState) >>
              storage.headSnapshot.map {
                expect.same(_, snapshot.some)
              }
        }
      }
    }
  }

  test("prepend - should return true if next snapshot creates a chain") { res =>
    implicit val (s, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case ((genesis, genesisState), (snapshot, currentState)) =>
            storage.prepend(genesis, genesisState) >>
              storage.prepend(snapshot, currentState).map(expect.same(_, true))
        }
      }
    }
  }

  test("prepend - should allow to start from any arbitrary snapshot") { res =>
    implicit val (s, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case (_, (snapshot, state)) =>
            storage.prepend(snapshot, state).map(expect.same(_, true))
        }
      }
    }
  }

  test("get - should return snapshot by ordinal") { res =>
    implicit val (s, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case ((genesis, genesisState), _) =>
            storage.prepend(genesis, genesisState) >>
              storage.get(genesis.ordinal).map(expect.same(_, genesis.some))
        }
      }
    }
  }

  test("get - should return snapshot by hash") { res =>
    implicit val (s, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case ((genesis, genesisState), _) =>
            storage.prepend(genesis, genesisState) >>
              genesis.value.hashF.flatMap { hash =>
                storage.get(hash).map(expect.same(_, genesis.some))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - subscriber should get latest balances") { res =>
    implicit val (s, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case ((genesis, genesisState), _) =>
            storage.prepend(genesis, genesisState) >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }

  test("getLatestBalancesStream - second subscriber should get latest balances") { res =>
    implicit val (s, sp) = res

    File.temporaryDirectory() { tmpDir =>
      mkStorage(tmpDir).flatMap { storage =>
        mkSnapshots.flatMap {
          case ((genesis, genesisState), _) =>
            storage.prepend(genesis, genesisState) >>
              storage.getLatestBalancesStream.take(1).compile.toList >>
              storage.getLatestBalancesStream.take(1).compile.toList.map {
                expect.same(_, List(Map.empty[address.Address, balance.Balance]))
              }
        }
      }
    }
  }
}
