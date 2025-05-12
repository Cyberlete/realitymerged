package org.reality.dag.l1.domain.block

import cats.effect.IO
import org.reality.dag.domain.block.NETBlock
import org.reality.net.domain.block.generators._
import org.reality.schema.transaction.{StandardTransaction, TransactionReference}
import org.reality.security.signature.Signed

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BlockRelationsSuite extends SimpleIOSuite with Checkers {

  test("when no relation between blocks then block is independent") {
    forall { (block: Signed[NETBlock], notRelatedBlock: Signed[NETBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[NETBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block is parent of second then block is dependent") {
    forall { (block: Signed[NETBlock], notRelatedBlock: Signed[NETBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[NETBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block is parent of first then block is independent") {
    forall { (block: Signed[NETBlock], notRelatedBlock: Signed[NETBlock]) =>
      for {
        notRelatedHashedBlock <- notRelatedBlock.toHashed[IO]
        hashedBlock <- block.copy(value = block.value.copy(parent = notRelatedHashedBlock.ownReference :: block.value.parent)).toHashed[IO]
        isRelated = (block: Signed[NETBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block has transaction reference used in second then block is dependent") {
    forall { (block: Signed[NETBlock], notRelatedBlock: Signed[NETBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[NETBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        hashedTxn <- block.transactions.head.toHashed[IO]
        relatedTxn = notRelatedBlock.transactions.head.copy(value =
          notRelatedBlock.transactions.head.value
            .asInstanceOf[StandardTransaction]
            .copy(parent = TransactionReference(hashedTxn.ordinal, hashedTxn.hash))
        )
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block has transaction reference used in first then block is independent") {
    forall { (block: Signed[NETBlock], notRelatedBlock: Signed[NETBlock]) =>
      for {
        hashedTxn <- notRelatedBlock.transactions.head.toHashed[IO]
        blockTxn = block.transactions.head
          .copy(value =
            block.transactions.head.value
              .asInstanceOf[StandardTransaction]
              .copy(parent = TransactionReference(hashedTxn.ordinal, hashedTxn.hash))
          )
        hashedBlock <- block.copy(value = block.value.copy(transactions = block.transactions.add(blockTxn))).toHashed[IO]
        isRelated = (block: Signed[NETBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block sends transaction to second then block is dependent") {
    forall { (block: Signed[NETBlock], notRelatedBlock: Signed[NETBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[NETBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        txn = block.transactions.head
        relatedTxn = notRelatedBlock.transactions.head
          .copy(value = notRelatedBlock.transactions.head.value.asInstanceOf[StandardTransaction].copy(source = txn.destination))
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when blocks is related with block reference then block is dependent") {
    forall { (block: Signed[NETBlock], notRelatedBlock: Signed[NETBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[NETBlock]) => BlockRelations.dependsOn(Set.empty, Set(hashedBlock.ownReference))(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

}
