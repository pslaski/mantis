package io.iohk.ethereum.blockchain.sync.regular

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestActor.AutoPilot
import akka.testkit.TestKit
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.PeersClient
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger._
import io.iohk.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import io.iohk.ethereum.network.EtcPeerManagerActor.{GetHandshakedPeers, HandshakedPeers}
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import io.iohk.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import io.iohk.ethereum.network.PeerEventBusActor.{PeerSelector, Subscribe}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.NewBlock
import io.iohk.ethereum.network.p2p.messages.PV62.BlockHeaderImplicits._
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.messages.PV63.{GetNodeData, NodeData}
import io.iohk.ethereum.network.{EtcPeerManagerActor, Peer, PeerEventBusActor}
import io.iohk.ethereum.ommers.OmmersPool.{AddOmmers, RemoveOmmers}
import io.iohk.ethereum.utils.Config.SyncConfig
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class RegularSyncSpec extends WordSpecLike with BeforeAndAfterEach with Matchers with MockFactory {
  type Fixture = RegularSyncFixture

  var testSystem: ActorSystem = _

  override def beforeEach: Unit =
    testSystem = ActorSystem()

  override def afterEach: Unit =
    TestKit.shutdownActorSystem(testSystem)

  "Regular Sync" when {
    "initializing" should {
      "subscribe for new blocks and new hashes" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peerEventBus.expectMsg(
          PeerEventBusActor.Subscribe(
            MessageClassifier(Set(NewBlock.code, NewBlockHashes.code), PeerSelector.AllPeers)))
      }
      "subscribe to handshaked peers list" in new Fixture(testSystem) {
        etcPeerManager.expectMsg(EtcPeerManagerActor.GetHandshakedPeers)
      }
    }

    "fetching blocks" should {
      "fetch headers and bodies concurrently" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peerEventBus.expectMsgClass(classOf[Subscribe])
        peerEventBus.reply(MessageFromPeer(NewBlock(testBlocks.last, Block.number(testBlocks.last)), defaultPeer.id))

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked.head.headers)))
        peersClient.expectMsgAllOfEq(
          blockHeadersRequest(1),
          PeersClient.Request.create(GetBlockBodies(testBlocksChunked.head.hashes), PeersClient.BestPeer)
        )
      }

      "blacklist peer which caused failed request" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgType[PeersClient.Request[GetBlockHeaders]]
        peersClient.reply(PeersClient.RequestFailed(defaultPeer, "a random reason"))
        peersClient.expectMsg(PeersClient.BlacklistPeer(defaultPeer.id, "a random reason"))
      }

      "blacklist peer which returns headers starting from one with higher number than expected" in new Fixture(
        testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked(1).headers)))
        peersClient.expectMsgPF() {
          case PeersClient.BlacklistPeer(id, _) if id == defaultPeer.id => true
        }
      }

      "blacklist peer which returns headers not forming a chain" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(
          PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked.head.headers.filter(_.number % 2 == 0))))
        peersClient.expectMsgPF() {
          case PeersClient.BlacklistPeer(id, _) if id == defaultPeer.id => true
        }
      }

      "wait for time defined in config until issuing a retry request due to no suitable peer" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.NoSuitablePeer)
        peersClient.expectNoMessage(syncConfig.syncRetryInterval)
        peersClient.expectMsgEq(blockHeadersRequest(0))
      }

      "not fetch new blocks if fetcher's queue reached size defined in configuration" in new Fixture(testSystem) {
        override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
          syncRetryInterval = testKitSettings.DefaultTimeout.duration,
          maxFetcherQueueSize = 1,
          blockBodiesPerRequest = 2,
          blockHeadersPerRequest = 2,
          blocksBatchSize = 2
        )

        regularSync ! RegularSync.Start

        peerEventBus.expectMsgClass(classOf[Subscribe])
        peerEventBus.reply(
          MessageFromPeer(NewBlock(testBlocks.last, testBlocks.last.header.difficulty), defaultPeer.id))

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked.head.headers)))
        peersClient.expectMsgEq(
          PeersClient.Request.create(GetBlockBodies(testBlocksChunked.head.hashes), PeersClient.BestPeer))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockBodies(testBlocksChunked.head.bodies)))

        peersClient.expectNoMessage()
      }
    }

    "resolving branches" should {
      trait FakeLedger { self: Fixture =>
        class FakeLedgerImpl extends TestLedgerImpl {
          override def importBlock(block: Block)(
              implicit blockExecutionContext: ExecutionContext): Future[BlockImportResult] = {
            val result: BlockImportResult = if (didTryToImportBlock(block)) {
              DuplicateBlock
            } else {
              if (importedBlocks.isEmpty || Block.isParentOf(bestBlock, block)) {
                importedBlocks.add(block)
                BlockImportedToTop(List(BlockData(block, Nil, block.header.difficulty)))
              } else if (Block.number(block) > Block.number(bestBlock)) {
                importedBlocks.add(block)
                BlockEnqueued
              } else {
                BlockImportFailed("foo")
              }
            }

            Future.successful(result)
          }

          override def resolveBranch(headers: scala.Seq[BlockHeader]): BranchResolutionResult = {
            val importedHashes = importedBlocks.map(Block.hash).toSet

            if (importedBlocks.isEmpty || (importedHashes.contains(headers.head.parentHash) && headers.last.number > Block
                .number(bestBlock))) {
              NewBetterBranch(Nil)
            } else {
              UnknownBranch
            }
          }
        }
      }

      "go back to earlier block in order to find a common parent with new branch" in new Fixture(testSystem)
      with FakeLedger {
        implicit val ec: ExecutionContext = system.dispatcher
        override lazy val ledger: TestLedgerImpl = new FakeLedgerImpl()

        val commonPart: List[Block] = testBlocks.take(syncConfig.blocksBatchSize)
        val alternativeBranch: List[Block] = getBlocks(syncConfig.blocksBatchSize * 2, commonPart.last)
        val alternativeBlocks: List[Block] = commonPart ++ alternativeBranch

        class BranchResolutionAutoPilot(didResponseWithNewBranch: Boolean, blocks: List[Block])
            extends PeersClientAutoPilot(blocks) {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetBlockHeaders(Left(nr), maxHeaders, _, _), _, _)
                if nr >= alternativeBranch.numberAtUnsafe(syncConfig.blocksBatchSize) && !didResponseWithNewBranch =>
              val responseHeaders = alternativeBranch.headers.filter(_.number >= nr).take(maxHeaders.toInt)
              sender ! PeersClient.Response(defaultPeer, BlockHeaders(responseHeaders))
              Some(new BranchResolutionAutoPilot(true, alternativeBlocks))
          }
        }

        peersClient.setAutoPilot(new BranchResolutionAutoPilot(didResponseWithNewBranch = false, testBlocks))

        Await.result(ledger.importBlock(genesis), remainingOrDefault)

        regularSync ! RegularSync.Start

        peerEventBus.expectMsgClass(classOf[Subscribe])
        peerEventBus.reply(MessageFromPeer(NewBlock(testBlocks.last, Block.number(testBlocks.last)), defaultPeer.id))

        awaitCond(ledger.bestBlock == alternativeBlocks.last, 15.seconds)
      }
    }

    "fetching state node" should {
      abstract class MissingStateNodeFixture(system: ActorSystem) extends Fixture(system) {
        val failingBlock: Block = testBlocksChunked.head.head
        ledger.setImportResult(failingBlock, () => Future.failed(new MissingNodeException(Block.hash(failingBlock))))
      }

      "blacklist peer which returns empty response" in new MissingStateNodeFixture(testSystem) {
        val failingPeer: Peer = peerByNumber(1)

        peersClient.setAutoPilot(new PeersClientAutoPilot {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetNodeData(_), _, _) =>
              sender ! PeersClient.Response(failingPeer, NodeData(Nil))
              None
          }
        })

        regularSync ! RegularSync.Start

        fishForBlacklistPeer(failingPeer)
      }
      "blacklist peer which returns invalid node" in new MissingStateNodeFixture(testSystem) {
        val failingPeer: Peer = peerByNumber(1)
        peersClient.setAutoPilot(new PeersClientAutoPilot {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetNodeData(_), _, _) =>
              sender ! PeersClient.Response(failingPeer, NodeData(List(ByteString("foo"))))
              None
          }
        })

        regularSync ! RegularSync.Start

        fishForBlacklistPeer(failingPeer)
      }
      "retry fetching node if validation failed" in new MissingStateNodeFixture(testSystem) {
        def fishForFailingBlockNodeRequest(): Boolean = peersClient.fishForSpecificMessage() {
          case PeersClient.Request(GetNodeData(hash :: Nil), _, _) if hash == Block.hash(failingBlock) => true
        }

        class WrongNodeDataPeersClientAutoPilot(var handledRequests: Int = 0) extends PeersClientAutoPilot {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetNodeData(_), _, _) =>
              val response = handledRequests match {
                case 0 => Some(PeersClient.Response(peerByNumber(1), NodeData(Nil)))
                case 1 => Some(PeersClient.Response(peerByNumber(2), NodeData(List(ByteString("foo")))))
                case _ => None
              }

              response.foreach(sender ! _)
              Some(new WrongNodeDataPeersClientAutoPilot(handledRequests + 1))
          }
        }

        peersClient.setAutoPilot(new WrongNodeDataPeersClientAutoPilot())

        regularSync ! RegularSync.Start

        fishForFailingBlockNodeRequest()
        fishForFailingBlockNodeRequest()
        fishForFailingBlockNodeRequest()
      }
      "save fetched node" in new Fixture(testSystem) {
        implicit val ec: ExecutionContext = system.dispatcher
        override lazy val blockchain: BlockchainImpl = stub[BlockchainImpl]
        override lazy val ledger: TestLedgerImpl = stub[TestLedgerImpl]
        val failingBlock: Block = testBlocksChunked.head.head
        peersClient.setAutoPilot(new PeersClientAutoPilot)

        (ledger.resolveBranch _).when(*).returns(NewBetterBranch(Nil))
        (ledger
          .importBlock(_: Block)(_: ExecutionContext))
          .when(*, *)
          .returns(Future.failed(new MissingNodeException(Block.hash(failingBlock))))

        var saveNodeWasCalled: Boolean = false
        val nodeData = List(ByteString(failingBlock.header.toBytes: Array[Byte]))
        (blockchain.getBestBlockNumber _).when().returns(0)
        (blockchain.getBlockHeaderByNumber _).when(*).returns(Some(genesis.header))
        (blockchain.saveNode _)
          .when(*, *, *)
          .onCall((hash, encoded, totalDifficulty) => {
            val expectedNode = nodeData.head

            hash should be(kec256(expectedNode))
            encoded should be(expectedNode.toArray)
            totalDifficulty should be(Block.number(failingBlock))

            saveNodeWasCalled = true
          })

        regularSync ! RegularSync.Start

        awaitCond(saveNodeWasCalled)
      }
    }

    "catching the top" should {
      "ignore mined blocks" in new Fixture(testSystem) {
        override lazy val ledger: TestLedgerImpl = stub[TestLedgerImpl]

        val minedBlock: Block = testBlocks.head

        regularSync ! RegularSync.Start
        regularSync ! RegularSync.MinedBlock(minedBlock)

        Thread.sleep(remainingOrDefault.toMillis)

        ommersPool.expectMsg(AddOmmers(List(minedBlock.header)))
        (ledger.importBlock(_: Block)(_: ExecutionContext)).verify(*, *).never()
      }
      "ignore new blocks if they are too new" in new Fixture(testSystem) {
        override lazy val ledger: TestLedgerImpl = stub[TestLedgerImpl]

        val newBlock: Block = testBlocks.last

        regularSync ! RegularSync.Start
        peerEventBus.expectMsgClass(classOf[Subscribe])

        peerEventBus.reply(MessageFromPeer(NewBlock(newBlock, 1), defaultPeer.id))

        Thread.sleep(remainingOrDefault.toMillis)

        (ledger.importBlock(_: Block)(_: ExecutionContext)).verify(*, *).never()
      }
    }

    "on top" should {
      abstract class OnTopFixture(system: ActorSystem) extends Fixture(system) {
        val newBlock: Block = getBlock(Block.number(testBlocks.last) + 1, testBlocks.last)

        override lazy val ledger: TestLedgerImpl = stub[TestLedgerImpl]

        var blockFetcher: ActorRef = _

        var importedNewBlock = false
        var importedLastTestBlock = false
        (ledger.resolveBranch _).when(*).returns(NewBetterBranch(Nil))
        (ledger
          .importBlock(_: Block)(_: ExecutionContext))
          .when(*, *)
          .onCall((block, _) => {
            if (block == newBlock) {
              importedNewBlock = true
              Future.successful(BlockImportedToTop(List(BlockData(newBlock, Nil, Block.number(newBlock)))))
            } else {
              if (block == testBlocks.last) {
                importedLastTestBlock = true
              }
              Future.successful(BlockImportedToTop(Nil))
            }
          })

        peersClient.setAutoPilot(new PeersClientAutoPilot)

        def waitForSubscription(): Unit = {
          peerEventBus.expectMsgClass(classOf[Subscribe])
          blockFetcher = peerEventBus.sender()
        }

        def sendLastTestBlockAsTop(): Unit = sendNewBlock(testBlocks.last)

        def sendNewBlock(block: Block = newBlock, peer: Peer = defaultPeer): Unit =
          blockFetcher ! MessageFromPeer(NewBlock(block, Block.number(block)), peer.id)

        def goToTop(): Unit = {
          regularSync ! RegularSync.Start

          waitForSubscription()
          sendLastTestBlockAsTop()

          awaitCond(importedLastTestBlock)
        }
      }

      "import received new block" in new OnTopFixture(testSystem) {
        goToTop()

        sendNewBlock()

        awaitCond(importedNewBlock)
      }
      "broadcast imported block" in new OnTopFixture(testSystem) {
        goToTop()

        etcPeerManager.expectMsg(GetHandshakedPeers)
        etcPeerManager.reply(HandshakedPeers(handshakedPeers))

        sendNewBlock()

        etcPeerManager.fishForSpecificMessageMatching() {
          case EtcPeerManagerActor.SendMessage(message, _) =>
            message.underlyingMsg match {
              case NewBlock(block, _) if block == newBlock => true
              case _ => false
            }
          case _ => false
        }
      }
      "update ommers for imported block" in new OnTopFixture(testSystem) {
        goToTop()

        sendNewBlock()

        ommersPool.expectMsg(RemoveOmmers(newBlock.header :: newBlock.body.uncleNodesList.toList))
      }
      "fetch hashes if received NewHashes message" in new OnTopFixture(testSystem) {
        goToTop()

        blockFetcher !
          MessageFromPeer(NewBlockHashes(List(BlockHash(Block.hash(newBlock), Block.number(newBlock)))), defaultPeer.id)

        peersClient.expectMsgPF() {
          case PeersClient.Request(GetBlockHeaders(_, _, _, _), _, _) => true
        }
      }
    }
  }
}