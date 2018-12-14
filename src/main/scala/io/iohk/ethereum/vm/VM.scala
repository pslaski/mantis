package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.utils.Logger

import scala.annotation.tailrec

class VM[W <: WorldStateProxy[W, S], S <: Storage[S]] extends Logger {

  type PC = ProgramContext[W, S]
  type PR = ProgramResult[W, S]
  type PS = ProgramState[W, S]

  /**
    * Executes a top-level program (transaction)
    * @param context context to be executed
    * @return result of the execution
   */
  def run(context: ProgramContext[W, S]): ProgramResult[W, S] = {
    {
      import context._
      import org.spongycastle.util.encoders.Hex
      log.trace(s"caller:  $callerAddr | recipient: $recipientAddr | gasPrice: $gasPrice | value: $value | inputData: ${Hex.toHexString(inputData.toArray)}")
    }

    context.recipientAddr match {
      case Some(recipientAddr) =>
        call(context, recipientAddr)

      case None =>
        create(context)._1
    }
  }

  /**
    * Message call - Θ function in YP
    */
  private[vm] def call(context: PC, ownerAddr: Address): PR =
    if (!isValidCall(context))
      invalidCallResult(context)
    else {
      require(context.recipientAddr.isDefined, "Recipient address must be defined for message call")

      def makeTransfer = context.world.transfer(context.callerAddr, context.recipientAddr.get, context.endowment)
      val world1 = if (context.doTransfer) makeTransfer else context.world
      val context1: PC = context.copy(world = world1)

      if (PrecompiledContracts.isDefinedAt(context1))
        PrecompiledContracts.run(context1)
      else {
        val code = world1.getCode(context.recipientAddr.get)
        val env = ExecEnv(context1, code, ownerAddr)

        val initialState: PS = ProgramState(this, context1, env)
        exec(initialState).toResult
      }
    }

  /**
    * Contract creation - Λ function in YP
    */
  private[vm] def create(context: PC): (PR, Address) =
    if (!isValidCall(context))
      (invalidCallResult(context), Address(0))
    else {
      require(context.recipientAddr.isEmpty, "recipient address must be empty for contract creation")
      require(context.doTransfer, "contract creation will always transfer funds")

      val newAddress = context.world.createAddress(context.callerAddr)
      val world1 = context.world.initialiseAccount(newAddress).transfer(context.callerAddr, newAddress, context.endowment)

      // EIP-684
      val conflict = context.world.nonEmptyCodeOrNonceAccount(newAddress)
      val code = if (conflict) ByteString(INVALID.code) else context.inputData

      val env = ExecEnv(context, code, newAddress).copy(inputData = ByteString.empty)

      val initialState: PS = ProgramState(this, context.copy(world = world1), env)
      val execResult = exec(initialState).toResult

      val newContractResult = if(execResult.isReverted) execResult else saveNewContract(context, newAddress, execResult, env.evmConfig)
      (newContractResult, newAddress)
    }

  @tailrec
  private def exec(state: ProgramState[W, S]): ProgramState[W, S] = {
    val byte = state.program.getByte(state.pc)
    state.config.byteToOpCode.get(byte) match {
      case Some(opCode) =>
        val newState = opCode.execute(state)
        import newState._
        if (log.isTraceEnabled) {
          log.trace(s"$opCode | pc: $pc | depth: ${env.callDepth} | gas: $gas | stack: $stack")
        }
        if (newState.halted)
          newState
        else
          exec(newState)

      case None =>
        state.withError(InvalidOpCode(byte)).halt
    }
  }

  protected def isValidCall(context: PC): Boolean =
    context.endowment <= context.world.getBalance(context.callerAddr) &&
      context.callDepth <= EvmConfig.MaxCallDepth

  private def invalidCallResult(context: PC): PR =
    ProgramResult(ByteString.empty, context.startGas, context.world, Set(), Nil, Nil, 0, Some(InvalidCall))

  private def saveNewContract(context: PC, address: Address, result: PR, config: EvmConfig): PR = {
    val contractCode = result.returnData
    val codeDepositCost = config.calcCodeDepositCost(contractCode)

    val maxCodeSizeExceeded = config.maxCodeSize.exists(codeSizeLimit => contractCode.size > codeSizeLimit)
    val codeStoreOutOfGas = result.gasRemaining < codeDepositCost

    if (maxCodeSizeExceeded || (codeStoreOutOfGas && config.exceptionalFailedCodeDeposit)) {
      // Code size too big or code storage causes out-of-gas with exceptionalFailedCodeDeposit enabled
      result.copy(error = Some(OutOfGas), gasRemaining = 0)
    } else if (codeStoreOutOfGas && !config.exceptionalFailedCodeDeposit) {
      // Code storage causes out-of-gas with exceptionalFailedCodeDeposit disabled
      result
    } else {
      // Code storage succeeded
      result.copy(
        gasRemaining = result.gasRemaining - codeDepositCost,
        world = result.world.saveCode(address, result.returnData))
    }
  }
}
