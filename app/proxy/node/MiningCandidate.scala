package proxy.node

import helpers.Helper
import io.circe.Json
import proxy.loggers.Logger
import proxy.status.ProxyStatus
import proxy.{Config, Pool, PoolShareQueue, Response}
import scalaj.http.HttpResponse

import scala.math.BigDecimal

class MiningCandidate(response: Response) {
  private var resp: Response = response
  private val responseBody: Json = Helper.ArrayByte(response.body).toJson

  /**
   * Get response for mining/candidate from the node response and handle proof
   * @return
   */
  def getResponse: String = {
    try {
      checkHeader()
    }
    catch {
      case error: ProxyStatus.PoolRequestException =>
        Logger.error(s"MiningCandidate - ${error.getMessage}")
    }
    miningCandidateBody()
  }

  /**
   * Clean /mining/candidate response and put pb in it
   *
   * @return [[String]]
   */
  private def miningCandidateBody(): String = {
    val cursor = Helper.ArrayByte(resp.body).toJson.hcursor
    val b: BigDecimal = cursor.downField("b").as[BigDecimal].getOrElse(BigDecimal("0"))
    s"""
       |{
       |  "msg": "${cursor.downField("msg").as[String].getOrElse("")}",
       |  "b": $b,
       |  "pk": "${cursor.downField("pk").as[String].getOrElse("")}",
       |  "pb": ${(b * Config.poolDifficultyFactor).toBigInt}
       |}
       |""".stripMargin
  }

  /**
   * Check if msg is changed
   * If true, then send proof to the pool server
   */
  private def checkHeader(): Unit = {
    val cursor = responseBody.hcursor

    val header = cursor.downField("msg").as[String].getOrElse({
      throw new Throwable("Can not read Key = \"msg\"")
    })
    if (header != Config.blockHeader) {
      if (sendProofToPool())
        Config.blockHeader = header
    }
  }

  /**
   * Get or create the proof (if is null) and send it to the pool server
   *
   * @return [[Boolean]] check if proof is accepted from the pool server
   */
  private def sendProofToPool(): Boolean = {
    val proof = getOrCreateProof()
    if (proof != "null") {
      Node.proof = proof
      val proofValidation = Pool.sendProof()

      if (proofValidation.isClientError) {
        Node.proof = ""
        return false
      }
      return true
    }
    false
  }

  /**
   * Get or create the proof
   *
   * @return [[String]] the proof
   */
  private def getOrCreateProof(): String = {
    val cursor = responseBody.hcursor
    var proof = cursor.downField("proof").as[Json].getOrElse(Json.Null).toString()

    if (proof == "null") {
      val pk = cursor.downField("pk").as[String].getOrElse({
        throw new Throwable("Can not read Key = \"pk\"")
      })

      try {
        Config.genTransactionInProcess = true
        proof = createProof(pk).toString()
      }
      catch {
        case error: Throwable =>
          throw error
      }
      finally {
        Config.genTransactionInProcess = false
      }
    }

    proof
  }

  /**
   * Generate transaction and make a new proof
   *
   * @return [[Json]] The body with proof
   */
  private def createProof(pk: String): Json = {
    try {
      val generatedTransaction: HttpResponse[Array[Byte]] = Node.generateTransaction()

      if (!generatedTransaction.isSuccess) {
        Logger.error(s"generateTransaction failed: ${Node.parseErrorResponse(generatedTransaction)}")
        throw new ProxyStatus.MiningDisabledException(s"Route /wallet/transaction/generate failed with error code ${generatedTransaction.code}")
      }

      val transaction = Helper.ArrayByte(generatedTransaction.body).toString
      val transactionValidation: HttpResponse[Array[Byte]] = Pool.sendTransaction(pk, transaction)

      if (transactionValidation.isSuccess) {
        val candidateWithTxsResponse = Node.candidateWithTxs(transaction)
        if (candidateWithTxsResponse.isSuccess)
          resp = Response(candidateWithTxsResponse)
        else
          Logger.error(s"candidateWithTxs failed: ${Node.parseErrorResponse(candidateWithTxsResponse)}")
        Helper.ArrayByte(candidateWithTxsResponse.body).toJson.hcursor.downField("proof").as[Json].getOrElse(Json.Null)
      }
      else {
        Logger.error(s"Transaction validation failed: ${Helper.ArrayByte(transactionValidation.body).toString}")
        Json.Null
      }
    }
    catch {
      case error: ProxyStatus.MiningDisabledException =>
        throw new Throwable(s"Creating proof failed (${error.getMessage})", error)
      case error: Throwable =>
        throw new ProxyStatus.MiningDisabledException(s"Creating proof failed: ${error.getMessage}")
    }
    finally {
      PoolShareQueue.unlock()
    }
  }
}
