package proxy.node

import helpers.Helper
import io.circe.{ACursor, HCursor, Json}
import play.api.mvc.{RawBuffer, Request}
import proxy.{Config, Response}
import scalaj.http.{Http, HttpResponse}
import proxy.status.ProxyStatus.{MiningDisabledException, NotEnoughBoxesException}

object Node {
  var pk: String = ""
  var unspentBoxes: Vector[Box] = _
  private var _lastProtectionAddress: String = _
  private var remainBoxesTransaction: Transaction = _
  private var txsList: Vector[Transaction] = Vector[Transaction]()
  private var _gapTransaction: Transaction = _
  private val _protectionScript: String =
    """
      |{"source": "(proveDlog(CONTEXT.preHeader.minerPk).propBytes == PK(\"<A>\").propBytes && PK(\"<A>\"))|| PK(\"<A2>\")"}
      |""".stripMargin

  def gapTransaction: Transaction = _gapTransaction

  def lastProtectionAddress: String = {
    if (_lastProtectionAddress == null)
      this.createProtectionScript()
    _lastProtectionAddress
  }

  private def protectionScript: String = {
    if (pk != "")
      this._protectionScript.replaceAll("<A>", this.pk).replaceAll("<A2>", Config.withdrawAddress)
    else
      ""
  }

  /**
   * Clear txs list
   */
  def resetTxsList(): Unit = {
    this.txsList = Vector[Transaction]()
  }

  private[this] var _proof: String = ""
  def proof: String = _proof

  /**
   * Update value of proof
   *
   * @param value [[String]] response of candidateWithTxs or miningCandidate with proof
   */
  def proof_=(value: String): Unit = {
    if (value != "") {
      val proofValue = Helper.convertToJson(value)
      val cursor: ACursor = proofValue.hcursor
      val txProof: ACursor = cursor.downField("txProofs").downArray

      _proof =
      s"""
         |{
         |    "pk": "${this.pk}",
         |    "msg_pre_image": "${cursor.downField("msgPreimage").as[String].getOrElse("")}",
         |    "leaf": "${txProof.downField("leaf").as[String].getOrElse("")}",
         |    "levels": ${txProof.downField("levels").as[Json].getOrElse(Json.Null)}
         |}
         |""".stripMargin
    }
  }

  /**
   * Send a request to a url with its all headers and body
   *
   * @param uri [[String]] Servers url
   * @param request [[Request[RawBuffer]]] The request to send
   * @return [[Response]] Response from the server
   */
  def sendRequest(uri: String, request: Request[RawBuffer]): Response = {
    // Prepare the request headers
    val reqHeaders: Seq[(String, String)] = request.headers.headers

    val response: HttpResponse[Array[Byte]] = {
      try {
        if (request.method == "GET") {
          Http(s"${Config.nodeConnection}$uri").headers(reqHeaders).asBytes
        }
        else {
          Http(s"${Config.nodeConnection}$uri").headers(reqHeaders).postData(Helper.RawBufferValue(request.body).toString).asBytes
        }
      }
      catch {
        case error: Throwable =>
          throw new Throwable(s"Node - $uri: ${error.getMessage}", error)
      }
    }

    // Convert the headers to Map[String, String] type
    val respHeaders: Map[String, String] = response.headers.map({
      case (key, value) =>
        key -> value.mkString(" ")
    })

    // Remove the ignored headers
    val contentType: String = respHeaders.getOrElse("Content-Type", "")
    val filteredHeaders: Map[String, String] = respHeaders.removed("Content-Type").removed("Content-Length")

    // Return the response
    Response(
      statusCode = response.code,
      headers = filteredHeaders,
      body = response.body,
      contentType = contentType
    )
  }

  /**
   * Send solution to the node
   *
   * @param request [[Request]] the request from the miner
   * @return [[Response]]
   */
  def sendSolution(request: Request[RawBuffer]): Response = {
    // Prepare the request headers
    val reqHeaders: Seq[(String, String)] = request.headers.headers
    val reqBody: HCursor = Helper.RawBufferValue(request.body).toJson.hcursor
    val body: String =
      s"""
         |{
         |  "pk": "${reqBody.downField("pk").as[String].getOrElse("")}",
         |  "w": "${reqBody.downField("w").as[String].getOrElse("")}",
         |  "n": "${reqBody.downField("n").as[String].getOrElse("")}",
         |  "d": ${reqBody.downField("d").as[BigInt].getOrElse("")}e0
         |}
         |""".stripMargin

    val rawResponse: HttpResponse[Array[Byte]] = Http(s"${Config.nodeConnection}${request.uri}").headers(reqHeaders).postData(body).asBytes
    Response(rawResponse)
  }

  /**
   * Send generate transaction request to the node
   *
   * @return [[HttpResponse]]
   */
  def generateTransaction(address: String = Config.walletAddress,
                          value: Long = Config.transactionRequestsValue,
                          inputsRaw: Vector[Box] = null):
  HttpResponse[Array[Byte]] = {
    val reqHeaders: Seq[(String, String)] = Seq(("api_key", Config.apiKey), ("Content-Type", "application/json"))
    val transactionGenerateBody: String =
      s"""
         |{
         |  "requests": [
         |    {
         |      "address": "$address",
         |      "value": $value
         |    }
         |  ],
         |  "fee": ${Config.transactionFee},
         |  "inputsRaw": [${if (inputsRaw != null) inputsRaw.map(f => s""""${f.bytes}"""").mkString(",") else ""}]
         |}
         |""".stripMargin
    Http(s"${Config.nodeConnection}/wallet/transaction/generate").headers(reqHeaders).postData(transactionGenerateBody).asBytes
  }

  /**
   * Send candidateWithTxs request to the node
   *
   * @param transaction [[String]] generated transaction
   * @return [[HttpResponse]]
   */
  def candidateWithTxs(transaction: String): HttpResponse[Array[Byte]] = {
    val reqHeaders: Seq[(String, String)] = Seq(("api_key", Config.apiKey), ("Content-Type", "application/json"))
    val candidateWithTxsBody: String =
      s"""
         |[
         |  ${this.txsList.map(tx => s"""{"transaction": ${tx.details}, "cost": 50000}""").mkString(",")}
         |]
         |""".stripMargin
    val response = Http(s"${Config.nodeConnection}/mining/candidateWithTxs").headers(reqHeaders).postData(candidateWithTxsBody).asBytes
    this.pk = Helper.ArrayByte(response.body).toJson.hcursor.downField("pk").as[String].getOrElse("")

    response
  }

  // $COVERAGE-OFF$
  def parseErrorResponse(response: HttpResponse[Array[Byte]]): String = {
    val body = Helper.ArrayByte(response.body).toJson
    val detail = body.hcursor.downField("detail").as[String].getOrElse("")

    val pattern = "\\([^()]*\\)".r
    var message = detail
    var newMessage = message
    while (message != newMessage) {
      newMessage = message
      message = pattern.replaceAllIn(newMessage, "")
    }
    message
  }
  // $COVERAGE-ON$

  /**
   * Create protection script
   */
  def createProtectionScript(): Unit = {
    val response = Http(s"${Config.nodeConnection}/script/p2sAddress").header("api_key", Config.apiKey).postData(this.protectionScript).asBytes

    if (response.isSuccess)
      _lastProtectionAddress = Helper.ArrayByte(response.body).toJson.hcursor.downField("address").as[String].getOrElse("")
  }

  /**
   * Fetch unspent boxes that have the same address as protection address
   */
  def fetchUnspentBoxes(): Unit = {
    val response = Http(s"${Config.nodeConnection}/wallet/boxes/unspent").header("api_key", Config.apiKey).asBytes

    if (response.isSuccess) {
      this.unspentBoxes = Helper.ArrayByte(response.body).toJson.asArray.getOrElse(Vector())
        .filter(item => {
          val boxAddress = item.hcursor.downField("address").as[String].getOrElse("")
          boxAddress == this.lastProtectionAddress
        })
        .map(item => {
          val cursor = item.hcursor
          val boxInfo = cursor.downField("box").as[Json].getOrElse(Json.Null).hcursor
          val boxId = boxInfo.downField("boxId").as[String].getOrElse("")
          val value = boxInfo.downField("value").as[Long].getOrElse(0L)
          new Box(boxId, value)
        })
    }
  }

  /**
   * Add the transaction to the transactions list
   * @param transaction [[Transaction]] the transaction to add
   */
  def addTransaction(transaction: Transaction): Unit = {
    this.txsList = this.txsList :+ transaction
  }

  /**
   * Check remain boxes transaction and add it to the transaction list if it had been mined
   */
  def checkRemainBoxesTransaction(): Unit = {
    if (this.remainBoxesTransaction != null && this.remainBoxesTransaction.isMined) {
      this.addTransaction(this.remainBoxesTransaction)
      this.remainBoxesTransaction = null
    }
  }

  /**
   * Get total value of unspent boxes
   *
   * @return [[Long]]
   */
  def unspentBoxesTotalValue: Long = {
    var total = 0L
    this.unspentBoxes.filter(f => !f.spent).foreach(f => total = total + f.boxValue)
    total
  }

  /**
   * Get total value of all boxes
   *
   * @return [[Long]]
   */
  def boxesTotalValue: Long = {
    var total = 0L
    this.unspentBoxes.foreach(f => total = total + f.boxValue)
    total
  }

  /**
   * Sum of transaction fee and transaction requests value
   *
   * @return [[Long]]
   */
  private def configuredTransactionTotalValue: Long = Config.transactionFee + Config.transactionRequestsValue

  /**
   * Check whether throw a NotEnoughBoxesException or a MiningDisableException from the response detail
   *
   * @param response [[HttpResponse]] the response to check
   * @return [[Throwable]]
   */
  private def notEnoughBoxesOrMiningDisable(response: HttpResponse[Array[Byte]]): Throwable = {
    val errorMsg = parseErrorResponse(response)
    if (errorMsg.map(_.toLower).contains("not enough boxes"))
      new NotEnoughBoxesException("Not enough boxes on remain boxes transaction")
    else
      new MiningDisabledException(s"Transaction for remain boxes failed: $errorMsg")
  }

  /**
   * Generate a transaction with unspent boxes
   * Throw MiningDisableException if there's not enough boxes
   *
   * @return [[HttpResponse]]
   */
  def generateTransactionWithUnspentBoxes(): HttpResponse[Array[Byte]] = {
    if (this.boxesTotalValue < this.configuredTransactionTotalValue) {
      val response = this.generateTransaction(address = this.lastProtectionAddress, value = Config.transactionRequestsValue - this.boxesTotalValue)
      if (response.isSuccess) {
        this._gapTransaction = Transaction(response.body)
        throw new MiningDisabledException("Should wait until transaction being mined", "TxsGen")
      } else {
        throw notEnoughBoxesOrMiningDisable(response)
      }
    }
    var total = 0L
    var boxesNeededForTransaction = Vector[Box]()
    this.unspentBoxes.iterator.takeWhile(_ => total <= this.configuredTransactionTotalValue).foreach(box => {
      total = total + box.boxValue
      boxesNeededForTransaction = boxesNeededForTransaction :+ box
      box.spent = true
    })

    this.generateTransaction(address = Config.walletAddress, inputsRaw = boxesNeededForTransaction)
  }

  /**
   * Generate a transaction for the remain boxes if needed
   */
  def handleRemainUnspentBoxes(): Unit = {
    if (this.unspentBoxesTotalValue < this.configuredTransactionTotalValue) {
      val response = this.generateTransaction(
        address = this.lastProtectionAddress,
        value = Config.transactionRequestsValue - this.unspentBoxesTotalValue
      )
      if (response.isSuccess) {
        this.remainBoxesTransaction = Transaction(response.body)
      } else {
        throw notEnoughBoxesOrMiningDisable(response)
      }
    }
  }
}
