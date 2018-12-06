package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.domain.{Address, TxLogEntry, UInt256}

object ProgramState {
  def apply[W <: WorldStateProxy[W, S], S <: Storage[S]](
      vm: VM[W, S],
      context: ProgramContext[W, S],
      env: ExecEnv): ProgramState[W, S] = {

    ProgramState(
      vm = vm,
      env = env,
      gas = env.startGas,
      world = context.world,
      addressesToDelete = context.initialAddressesToDelete)
  }
}

/**
  * Intermediate state updated with execution of each opcode in the program
  *
  * @param vm the VM
  * @param env program constants
  * @param gas current gas for the execution
  * @param world world state
  * @param addressesToDelete list of addresses of accounts scheduled to be deleted
  * @param stack current stack
  * @param memory current memory
  * @param pc program counter - an index of the opcode in the program to be executed
  * @param returnData data to be returned from the program execution
  * @param gasRefund the amount of gas to be refunded after execution (not sure if a separate field is required)
  * @param internalTxs list of internal transactions (for debugging/tracing)
  * @param halted a flag to indicate program termination
  * @param error indicates whether the program terminated abnormally
  */
case class ProgramState[W <: WorldStateProxy[W, S], S <: Storage[S]](
  vm: VM[W, S],
  env: ExecEnv,
  gas: BigInt,
  world: W,
  addressesToDelete: Set[Address],
  stack: Stack = Stack.empty(),
  memory: Memory = Memory.empty,
  pc: Int = 0,
  returnData: ByteString = ByteString.empty,
  gasRefund: BigInt = 0,
  internalTxs: Vector[InternalTransaction] = Vector.empty,
  logs: Vector[TxLogEntry] = Vector.empty,
  halted: Boolean = false,
  error: Option[ProgramError] = None
) {

  def config: EvmConfig = env.evmConfig

  def ownAddress: Address = env.ownerAddr

  def ownBalance: UInt256 = world.getBalance(ownAddress)

  def storage: S = world.getStorage(ownAddress)

  def gasUsed: BigInt = env.startGas - gas

  def withWorld(updated: W): ProgramState[W, S] =
    copy(world = updated)

  def withStorage(updated: S): ProgramState[W, S] =
    withWorld(world.saveStorage(ownAddress, updated))

  def program: Program = env.program

  def inputData: ByteString = env.inputData

  def spendGas(amount: BigInt): ProgramState[W, S] =
    copy(gas = gas - amount)

  def refundGas(amount: BigInt): ProgramState[W, S] =
    copy(gasRefund = gasRefund + amount)

  def step(i: Int = 1): ProgramState[W, S] =
    copy(pc = pc + i)

  def goto(i: Int): ProgramState[W, S] =
    copy(pc = i)

  def withStack(stack: Stack): ProgramState[W, S] =
    copy(stack = stack)

  def withMemory(memory: Memory): ProgramState[W, S] =
    copy(memory = memory)

  def withError(error: ProgramError): ProgramState[W, S] =
    copy(error = Some(error), halted = true)

  def withReturnData(data: ByteString): ProgramState[W, S] =
    copy(returnData = data)

  def withAddressToDelete(addr: Address): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete + addr)

  def withAddressesToDelete(addresses: Set[Address]): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete ++ addresses)

  def withLog(log: TxLogEntry): ProgramState[W, S] =
    copy(logs = logs :+ log)

  def withLogs(log: Seq[TxLogEntry]): ProgramState[W, S] =
    copy(logs = logs ++ log)

  def withInternalTxs(txs: Seq[InternalTransaction]): ProgramState[W, S] =
    if (config.traceInternalTransactions) copy(internalTxs = internalTxs ++ txs) else this

  def halt: ProgramState[W, S] =
    copy(halted = true)

  def toResult: ProgramResult[W, S] =
    ProgramResult[W, S](
      returnData,
      if (error.exists(_ != RevertTransaction)) 0 else gas,
      world,
      addressesToDelete,
      logs,
      internalTxs,
      gasRefund,
      error)
}
