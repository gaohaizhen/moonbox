package moonbox.application.interactive.netty

import java.io.{PrintWriter, StringWriter}

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.util.ReferenceCountUtil
import moonbox.application.interactive.Runner
import moonbox.common.MbLogging
import moonbox.message.protobuf.{InteractiveNextResultOutbound, ProtoMessage}
import moonbox.protocol.util.ProtoOutboundMessageBuilder

import scala.collection.mutable

class DataFetchServerProtoHandler(sessionIdToJobRunner: mutable.Map[String, Runner]) extends ChannelInboundHandlerAdapter with MbLogging {

  override def channelRead(ctx: ChannelHandlerContext, msg: Any) = {
    try {
      msg match {
        case m: ProtoMessage => handleProtoMessage(ctx, m)
        case other => logWarning(s"Unknown message type $other")
      }
    } finally {
      ReferenceCountUtil.release(msg)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) = {
    val sw = new StringWriter()
    cause.printStackTrace(new PrintWriter(sw))
    logError(sw.toString)
    super.exceptionCaught(ctx, cause)
    ctx.close
  }

  private def handleProtoMessage(ctx: ChannelHandlerContext, message: ProtoMessage): Unit = {
    val msgId = message.getMessageId
    if (message.hasInteractiveNextResultInbound) {
      val in = message.getInteractiveNextResultInbound
      val sessionId = in.getSessionId
      logInfo(s"Received InteractiveNextResultInbound(SessionId=$sessionId)")
      // TODO: fetch data from runner
      sessionIdToJobRunner.get(sessionId) match {
        case Some(runner) =>
			val response = try {
				val resultData = runner.fetchResultData()
				ProtoOutboundMessageBuilder.interactiveNextResultOutbound(null, sessionId, resultData.schema, resultData.data, resultData.hasNext)
			} catch {
				case e: Exception =>
					val msg = if (e.getMessage != null) {
						e.getMessage
					} else {
						e.getStackTrace.map(_.toString).mkString("\n")
					}
					ProtoOutboundMessageBuilder.interactiveNextResultOutbound(msg, null)
			}
          ctx.writeAndFlush(buildProtoMessage(msgId, response))
        case None =>
          val errorMsg = s"DataFetch ERROR: Invalid sessionId or session lost, SessionId=$sessionId"
          val toResp = ProtoOutboundMessageBuilder.interactiveNextResultOutbound(errorMsg, null)
          ctx.writeAndFlush(buildProtoMessage(msgId, toResp))
      }
    } else {
      logWarning(s"Received unsupported message type: $message, do noting!")
    }
  }

  private def buildProtoMessage(messageId: Long, outbound: InteractiveNextResultOutbound): ProtoMessage = {
    ProtoMessage.newBuilder().setMessageId(messageId).setInteractiveNextResultOutbound(outbound).build()
  }
}
