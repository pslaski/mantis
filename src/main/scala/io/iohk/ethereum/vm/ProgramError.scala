package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.domain.UInt256

/**
  * Marker trait for errors that may occur during program execution
  */
sealed trait ProgramError
case class  InvalidOpCode(code: Byte) extends ProgramError {
  override def toString: String =
    f"${getClass.getSimpleName}(0x${code.toInt & 0xff}%02x)"
}
case object OutOfGas extends ProgramError
case class InvalidJump(dest: UInt256) extends ProgramError {
  override def toString: String =
    f"${getClass.getSimpleName}(${dest.toHexString})"
}

case object RevertTransaction extends ProgramError

sealed trait StackError extends ProgramError
case object StackOverflow extends StackError
case object StackUnderflow extends StackError

case object InvalidCall extends ProgramError

case class WithReturnCode(returnCode: ByteString) extends ProgramError
