package com.kakao.s2graph.core

import java.util

import com.kakao.s2graph.core.mysqls._
import com.kakao.s2graph.core.types._
import com.kakao.s2graph.logger
import com.stumbleupon.async.Deferred
import org.apache.hadoop.hbase.client.{Delete, Put}
import org.apache.hadoop.hbase.util.Bytes
import org.hbase.async._
import play.api.libs.json.Json

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.util.hashing.MurmurHash3
import scala.util.{Failure, Random, Success, Try}


case class EdgeWithIndexInverted(srcVertex: Vertex,
                                 tgtVertex: Vertex,
                                 labelWithDir: LabelWithDirection,
                                 op: Byte,
                                 version: Long,
                                 props: Map[Byte, InnerValLikeWithTs],
                                 pendingEdgeOpt: Option[Edge] = None) {

  import Graph.edgeCf
  import HBaseSerializable._

  //  logger.error(s"EdgeWithIndexInverted${this.toString}")
  val schemaVer = label.schemaVersion
  lazy val rowKey = EdgeRowKey(VertexId.toSourceVertexId(srcVertex.id), labelWithDir, LabelIndex.DefaultSeq, isInverted = true)(version = schemaVer)
  lazy val qualifier = EdgeQualifierInverted(VertexId.toTargetVertexId(tgtVertex.id))(version = schemaVer)
  lazy val value = EdgeValueInverted(op, props.toList)(version = schemaVer)
  lazy val valueBytes = pendingEdgeOpt match {
    case None => value.bytes
    case Some(pendingEdge) =>

      val opBytes = Array.fill(1)(op)
      val versionBytes = Bytes.toBytes(version)
      val propsBytes = propsToKeyValuesWithTs(pendingEdge.propsWithTs.toSeq)

      Bytes.add(Bytes.add(EdgeValueInverted(op, props.toList)(version = schemaVer).bytes, opBytes), versionBytes, propsBytes)
  }

  // only for toString.
  lazy val label = Label.findById(labelWithDir.labelId)
  lazy val propsWithoutTs = props.mapValues(_.innerVal)

  def buildPut() = {
    val put = new Put(rowKey.bytes)
    put.addColumn(edgeCf, qualifier.bytes, version, value.bytes)
  }

  def buildPutAsync() = {
    new PutRequest(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, qualifier.bytes, valueBytes, version)
  }

  def buildDeleteAsync() = {
    val ret = new DeleteRequest(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, qualifier.bytes, version)
    ret
  }

  def withNoPendingEdge() = copy(pendingEdgeOpt = None)

  def withPendingEdge(pendingEdgeOpt: Option[Edge]) = copy(pendingEdgeOpt = pendingEdgeOpt)
}

case class EdgeWithIndex(srcVertex: Vertex,
                         tgtVertex: Vertex,
                         labelWithDir: LabelWithDirection,
                         op: Byte,
                         ts: Long,
                         labelIndexSeq: Byte,
                         props: Map[Byte, InnerValLike]) extends JSONParser {

  import Graph.edgeCf

  lazy val label = Label.findById(labelWithDir.labelId)
  val schemaVer = label.schemaVersion
  lazy val labelIndex = LabelIndex.findByLabelIdAndSeq(labelWithDir.labelId, labelIndexSeq).get
  lazy val defaultIndexMetas = labelIndex.sortKeyTypes.map { meta =>
    val innerVal = toInnerVal(meta.defaultValue, meta.dataType, schemaVer)
    meta.seq -> innerVal
  }.toMap

  lazy val labelIndexMetaSeqs = labelIndex.metaSeqs

  /** TODO: make sure call of this class fill props as this assumes */
  lazy val orders = for (k <- labelIndexMetaSeqs) yield {
    props.get(k) match {
      case None =>

        /**
         * TODO: agly hack
         * now we double store target vertex.innerId/srcVertex.innerId for easy development. later fix this to only store id once
         */
        val v = k match {
          case LabelMeta.timeStampSeq => InnerVal.withLong(ts, schemaVer)
          case LabelMeta.toSeq => tgtVertex.innerId
          case LabelMeta.fromSeq => //srcVertex.innerId
            // for now, it does not make sense to build index on srcVertex.innerId since all edges have same data.
            throw new RuntimeException("_from on indexProps is not supported")
          case _ => defaultIndexMetas(k)
        }

        k -> v
      case Some(v) => k -> v
    }
  }

  lazy val ordersKeyMap = orders.map { case (byte, _) => byte }.toSet
  lazy val metas = for ((k, v) <- props if !ordersKeyMap.contains(k)) yield k -> v

  lazy val rowKey = EdgeRowKey(VertexId.toSourceVertexId(srcVertex.id), labelWithDir, labelIndexSeq, isInverted = false)(schemaVer)

  lazy val qualifier = EdgeQualifier(orders, VertexId.toTargetVertexId(tgtVertex.id), op)(label.schemaVersion)
  lazy val value = EdgeValue(metas.toList)(label.schemaVersion)

  lazy val hasAllPropsForIndex = orders.length == labelIndexMetaSeqs.length

  def buildPuts(): List[Put] = {
    if (!hasAllPropsForIndex) {
      logger.error(s"$this dont have all props for index")
      List.empty[Put]
    } else {
      val put = new Put(rowKey.bytes)
      //    logger.debug(s"$this")
      //      logger.debug(s"EdgeWithIndex.buildPut: $rowKey, $qualifier, $value")
      put.addColumn(edgeCf, qualifier.bytes, ts, value.bytes)
      List(put)
    }
  }

  def buildPutsAsync(): List[HBaseRpc] = {
    if (!hasAllPropsForIndex) {
      logger.error(s"$this dont have all props for index")
      List.empty[PutRequest]
    } else {
      val put = new PutRequest(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, qualifier.bytes, value.bytes, ts)
      //      logger.debug(s"$put")
      List(put)
    }
  }

  def buildIncrementsBulk(amount: Long = 1L): List[Put] = {
    val put = new Put(rowKey.bytes)
    put.addColumn(edgeCf, Array.empty[Byte], Bytes.toBytes(amount))
    List(put)
  }

  def buildIncrementsAsync(amount: Long = 1L): List[HBaseRpc] = {
    if (!hasAllPropsForIndex) {
      logger.error(s"$this dont have all props for index")
      List.empty[AtomicIncrementRequest]
    } else {
      val incr = new AtomicIncrementRequest(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, Array.empty[Byte], amount)
      List(incr)
    }
  }

  def buildIncrementsCountAsync(amount: Long = 1L): List[HBaseRpc] = {
    if (!hasAllPropsForIndex) {
      logger.error(s"$this dont have all props for index")
      List.empty[AtomicIncrementRequest]
    } else {
      val incr = new AtomicIncrementRequest(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, qualifier.bytes, amount)
      //      logger.debug(s"$incr")
      List(incr)
    }
  }

  def buildDeletes(): List[Delete] = {
    if (!hasAllPropsForIndex) List.empty[Delete]
    else {
      val delete = new Delete(rowKey.bytes)
      delete.addColumns(edgeCf, qualifier.bytes, ts)
      List(delete)
    }
  }

  def buildDeletesAsync(): List[HBaseRpc] = {
    if (!hasAllPropsForIndex) List.empty[DeleteRequest]
    else {
      val deleteRequest = new DeleteRequest(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, qualifier.bytes, ts)
      //      logger.error(s"$deleteRequest, $ts")
      List(deleteRequest)
    }
  }

  def buildDeleteRowAsync(): List[HBaseRpc] = {
    if (!hasAllPropsForIndex) List.empty[DeleteRequest]
    else {
      val deleteRequest = new DeleteRequest(label.hbaseTableName.getBytes, rowKey.bytes, ts)
      //            logger.error(s"DeleteRow: ${rowKey}, $deleteRequest, $ts")
      List(deleteRequest)
    }
  }

  def buildDegreeDeletesAsync(): List[HBaseRpc] = {
    if (!hasAllPropsForIndex) List.empty[DeleteRequest]
    else {
      List(new DeleteRequest(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, Array.empty[Byte], ts))
    }
  }
}

case class Edge(srcVertex: Vertex,
                tgtVertex: Vertex,
                labelWithDir: LabelWithDirection,
                op: Byte = GraphUtil.defaultOpByte,
                ts: Long = System.currentTimeMillis(),
                version: Long = System.currentTimeMillis(),
                propsWithTs: Map[Byte, InnerValLikeWithTs] = Map.empty[Byte, InnerValLikeWithTs],
                pendingEdgeOpt: Option[Edge] = None,
                parentEdges: Seq[EdgeWithScore] = Nil,
                originalEdgeOpt: Option[Edge] = None) extends GraphElement with JSONParser {

  val schemaVer = label.schemaVersion


  def props = propsWithTs.mapValues(_.innerVal)

  def relatedEdges = {
    if (labelWithDir.isDirected) List(this, duplicateEdge)
    else {
      val outDir = labelWithDir.copy(dir = GraphUtil.directions("out"))
      val base = copy(labelWithDir = outDir)
      List(base, base.reverseSrcTgtEdge)
    }
  }

  def srcForVertex = {
    val belongLabelIds = Seq(labelWithDir.labelId)
    if (labelWithDir.dir == GraphUtil.directions("in")) {
      Vertex(VertexId(label.tgtColumn.id.get, tgtVertex.innerId), tgtVertex.ts, tgtVertex.props, belongLabelIds = belongLabelIds)
    } else {
      Vertex(VertexId(label.srcColumn.id.get, srcVertex.innerId), srcVertex.ts, srcVertex.props, belongLabelIds = belongLabelIds)
    }
  }

  def tgtForVertex = {
    val belongLabelIds = Seq(labelWithDir.labelId)
    if (labelWithDir.dir == GraphUtil.directions("in")) {
      Vertex(VertexId(label.srcColumn.id.get, srcVertex.innerId), srcVertex.ts, srcVertex.props, belongLabelIds = belongLabelIds)
    } else {
      Vertex(VertexId(label.tgtColumn.id.get, tgtVertex.innerId), tgtVertex.ts, tgtVertex.props, belongLabelIds = belongLabelIds)
    }
  }

  def duplicateEdge = reverseSrcTgtEdge.reverseDirEdge

  def reverseDirEdge = copy(labelWithDir = labelWithDir.dirToggled)

  def reverseSrcTgtEdge = copy(srcVertex = tgtVertex, tgtVertex = srcVertex)

  def label = Label.findById(labelWithDir.labelId)

  def labelOrders = LabelIndex.findByLabelIdAll(labelWithDir.labelId)

  override def serviceName = label.serviceName

  override def queueKey = Seq(ts.toString, tgtVertex.serviceName).mkString("|")

  override def queuePartitionKey = Seq(srcVertex.innerId, tgtVertex.innerId).mkString("|")

  override def isAsync = label.isAsync

  def propsPlusTs = propsWithTs.get(LabelMeta.timeStampSeq) match {
    case Some(_) => props
    case None => props ++ Map(LabelMeta.timeStampSeq -> InnerVal.withLong(ts, schemaVer))
  }

  def propsPlusTsValid = propsPlusTs.filter(kv => kv._1 >= 0)

  def edgesWithIndex = for (labelOrder <- labelOrders) yield {
    EdgeWithIndex(srcVertex, tgtVertex, labelWithDir, op, version, labelOrder.seq, propsPlusTs)
  }

  def edgesWithIndexValid = for (labelOrder <- labelOrders) yield {
    EdgeWithIndex(srcVertex, tgtVertex, labelWithDir, op, version, labelOrder.seq, propsPlusTsValid)
  }

  def edgesWithIndexValid(newOp: Byte) = for (labelOrder <- labelOrders) yield {
    EdgeWithIndex(srcVertex, tgtVertex, labelWithDir, newOp, version, labelOrder.seq, propsPlusTsValid)
  }

  /** force direction as out on invertedEdge */
  def toInvertedEdgeHashLike: EdgeWithIndexInverted = {
    val (smaller, larger) = (srcForVertex, tgtForVertex)

    val newLabelWithDir = LabelWithDirection(labelWithDir.labelId, GraphUtil.directions("out"))

    val ret = EdgeWithIndexInverted(smaller, larger, newLabelWithDir, op, version, propsWithTs ++
      Map(LabelMeta.timeStampSeq -> InnerValLikeWithTs(InnerVal.withLong(ts, schemaVer), ts)), pendingEdgeOpt)
    ret
  }

  override def hashCode(): Int = {
    MurmurHash3.stringHash(srcVertex.innerId + "," + labelWithDir + "," + tgtVertex.innerId)
  }

  override def equals(other: Any): Boolean = other match {
    case e: Edge =>
      srcVertex.innerId == e.srcVertex.innerId &&
        tgtVertex.innerId == e.tgtVertex.innerId &&
        labelWithDir == e.labelWithDir
    case _ => false
  }

  def propsWithName = for {
    (seq, v) <- props
    meta <- label.metaPropsMap.get(seq) if seq > 0
    jsValue <- innerValToJsValue(v, meta.dataType)
  } yield meta.name -> jsValue

  def updateTgtVertex(id: InnerValLike) = {
    val newId = TargetVertexId(tgtVertex.id.colId, id)
    val newTgtVertex = Vertex(newId, tgtVertex.ts, tgtVertex.props)
    Edge(srcVertex, newTgtVertex, labelWithDir, op, ts, version, propsWithTs)
  }


  def toJson = {}

  def rank(r: RankParam): Double =
    if (r.keySeqAndWeights.size <= 0) 1.0f
    else {
      var sum: Double = 0

      for ((seq, w) <- r.keySeqAndWeights) {
        seq match {
          case LabelMeta.countSeq => sum += 1
          case _ => {
            propsWithTs.get(seq) match {
              case None => // do nothing
              case Some(innerValWithTs) => {
                val cost = try innerValWithTs.innerVal.toString.toDouble catch {
                  case e: Exception =>
                    logger.error("toInnerval failed in rank", e)
                    1.0
                }
                sum += w * cost
              }
            }
          }
        }
      }
      sum
    }

  def toLogString: String = {
    val ret =
      if (propsWithName.nonEmpty)
        List(ts, GraphUtil.fromOp(op), "e", srcVertex.innerId, tgtVertex.innerId, label.label, Json.toJson(propsWithName))
      else
        List(ts, GraphUtil.fromOp(op), "e", srcVertex.innerId, tgtVertex.innerId, label.label)

    ret.mkString("\t")
  }

  override def buildPutsAll(): List[HBaseRpc] =
    Nil

  //    EdgeWriter(this).buildPutsAll()
}

case class EdgeWriter(edge: Edge) {
  implicit val ex = Graph.executionContext

  val MaxTryNum = Graph.MaxRetryNum
  val op = edge.op
  val label = edge.label
  val labelWithDir = edge.labelWithDir

  /**
   * methods for build mutations.
   */
  def buildVertexPuts(): List[Put] = edge.srcForVertex.buildPuts ++ edge.tgtForVertex.buildPuts

  def buildVertexPutsAsync(): List[PutRequest] = edge.srcForVertex.buildPutsAsync() ++ edge.tgtForVertex.buildPutsAsync()

  def insertBulkForLoader(createRelEdges: Boolean = true) = {
    val relEdges = if (createRelEdges) edge.relatedEdges else List(edge)
    val puts = edge.toInvertedEdgeHashLike.buildPutAsync() :: relEdges.flatMap { relEdge =>
      relEdge.edgesWithIndex.flatMap(e => e.buildPutsAsync())
    }

    val incrs = relEdges.flatMap { relEdge =>
      relEdge.edgesWithIndex.flatMap(e => e.buildIncrementsAsync())
    }

    val rets = puts ++ incrs
    //    logger.debug(s"Edge.insert(): $rets")
    rets
  }

}

case class EdgeUpdate(indexedEdgeMutations: List[HBaseRpc] = List.empty[HBaseRpc],
                      invertedEdgeMutations: List[HBaseRpc] = List.empty[HBaseRpc],
                      edgesToDelete: List[EdgeWithIndex] = List.empty[EdgeWithIndex],
                      edgesToInsert: List[EdgeWithIndex] = List.empty[EdgeWithIndex],
                      newInvertedEdge: Option[EdgeWithIndexInverted] = None) {

  def toLogString: String = {
    val indexedEdgeSize = s"indexedEdgeMutationSize: ${indexedEdgeMutations.size}"
    val invertedEdgeSize = s"invertedEdgeMutationSize: ${invertedEdgeMutations.size}"
    val deletes = s"deletes: ${edgesToDelete.map(e => e.toString).mkString("\n")}"
    val inserts = s"inserts: ${edgesToInsert.map(e => e.toString).mkString("\n")}"
    val updates = s"snapshot: $newInvertedEdge"

    List(indexedEdgeSize, invertedEdgeSize, deletes, inserts, updates).mkString("\n\n")
  }

  def increments: List[HBaseRpc] = {
    (edgesToDelete.isEmpty, edgesToInsert.isEmpty) match {
      case (true, true) =>

        /** when there is no need to update. shouldUpdate == false */
        List.empty[AtomicIncrementRequest]
      case (true, false) =>

        /** no edges to delete but there is new edges to insert so increase degree by 1 */
        edgesToInsert.flatMap { e => e.buildIncrementsAsync() }
      case (false, true) =>

        /** no edges to insert but there is old edges to delete so decrease degree by 1 */
        edgesToDelete.flatMap { e => e.buildIncrementsAsync(-1L) }
      case (false, false) =>

        /** update on existing edges so no change on degree */
        List.empty[AtomicIncrementRequest]
    }
  }
}

object Edge extends JSONParser {

  import HBaseDeserializable._
  import HBaseSerializable._


  //  val initialVersion = 2L
  val incrementVersion = 1L
  val minTsVal = 0L
  // FIXME:

  /** now version information is required also **/
  type PropsPairWithTs = (Map[Byte, InnerValLikeWithTs], Map[Byte, InnerValLikeWithTs], Long, String)

  def buildUpsert(invertedEdge: Option[Edge], requestEdge: Edge): EdgeUpdate = {
    assert(requestEdge.op == GraphUtil.operations("insert"))
    buildOperation(invertedEdge, requestEdge)(buildUpsert)
  }

  def buildUpdate(invertedEdge: Option[Edge], requestEdge: Edge): EdgeUpdate = {
    assert(requestEdge.op == GraphUtil.operations("update"))
    buildOperation(invertedEdge, requestEdge)(buildUpdate)
  }

  def buildDelete(invertedEdge: Option[Edge], requestEdge: Edge): EdgeUpdate = {
    assert(requestEdge.op == GraphUtil.operations("delete"))
    buildOperation(invertedEdge, requestEdge)(buildDelete)
  }

  def buildIncrement(invertedEdge: Option[Edge], requestEdge: Edge): EdgeUpdate = {
    assert(requestEdge.op == GraphUtil.operations("increment"))
    buildOperation(invertedEdge, requestEdge)(buildIncrement)
  }

  def buildInsertBulk(invertedEdge: Option[Edge], requestEdge: Edge): EdgeUpdate = {
    assert(invertedEdge.isEmpty)
    assert(requestEdge.op == GraphUtil.operations("insertBulk") || requestEdge.op == GraphUtil.operations("insert"))
    buildOperation(None, requestEdge)(buildUpsert)
  }

  def buildDeleteBulk(invertedEdge: Option[Edge], requestEdge: Edge): EdgeUpdate = {
    assert(invertedEdge.isEmpty)
    assert(requestEdge.op == GraphUtil.operations("delete"))

    val edgesToDelete = requestEdge.relatedEdges.flatMap { relEdge => relEdge.edgesWithIndexValid }

    val edgesToInsert = Nil

    val edgeInverted = Option(requestEdge.toInvertedEdgeHashLike)

    val deleteMutations = edgesToDelete.flatMap(edge => edge.buildDeletesAsync())
    val insertMutations = Nil

    val indexedEdgeMutations = deleteMutations ++ insertMutations
    val invertedEdgeMutations = List(requestEdge.toInvertedEdgeHashLike.buildDeleteAsync())


    EdgeUpdate(indexedEdgeMutations,
      invertedEdgeMutations, edgesToDelete, edgesToInsert, edgeInverted)

  }

  def buildOperation(invertedEdge: Option[Edge], requestEdge: Edge)(f: PropsPairWithTs => (Map[Byte, InnerValLikeWithTs], Boolean)) = {
    //            logger.debug(s"oldEdge: ${invertedEdge.map(_.toStringRaw)}")
    //            logger.debug(s"requestEdge: ${requestEdge.toStringRaw}")

    val oldPropsWithTs = if (invertedEdge.isEmpty) Map.empty[Byte, InnerValLikeWithTs] else invertedEdge.get.propsWithTs

    val oldTs = invertedEdge.map(e => e.ts).getOrElse(minTsVal)

    if (oldTs == requestEdge.ts) {
      logger.info(s"duplicate timestamp on same edge. $requestEdge")
      EdgeUpdate()
    } else {
      val (newPropsWithTs, shouldReplace) =
        f(oldPropsWithTs, requestEdge.propsWithTs, requestEdge.ts, requestEdge.schemaVer)

      if (!shouldReplace) {
        logger.info(s"drop request $requestEdge becaseu shouldReplace is $shouldReplace")
        EdgeUpdate()
      } else {

        val maxTsInNewProps = newPropsWithTs.map(kv => kv._2.ts).max
        val newOp = if (maxTsInNewProps > requestEdge.ts) {
          invertedEdge match {
            case None => requestEdge.op
            case Some(old) => old.op
          }
        } else {
          requestEdge.op
        }

        val newEdgeVersion = invertedEdge.map(e => e.version + incrementVersion).getOrElse(requestEdge.ts)

        val maxTs = if (oldTs > requestEdge.ts) oldTs else requestEdge.ts
        val newEdge = Edge(requestEdge.srcVertex, requestEdge.tgtVertex, requestEdge.labelWithDir,
          newOp, maxTs, newEdgeVersion, newPropsWithTs)

        buildReplace(invertedEdge, newEdge, newPropsWithTs)
      }
    }

  }

  /**
   * delete invertedEdge.edgesWithIndex
   * insert requestEdge.edgesWithIndex
   * update requestEdge.edgesWithIndexInverted
   */
  def buildReplace(invertedEdge: Option[Edge], requestEdge: Edge, newPropsWithTs: Map[Byte, InnerValLikeWithTs]): EdgeUpdate = {

    val edgesToDelete = invertedEdge match {
      case Some(e) if e.op != GraphUtil.operations("delete") =>
        e.relatedEdges.flatMap { relEdge => relEdge.edgesWithIndexValid }
      //      case Some(e) => e.edgesWithIndexValid
      case _ =>
        // nothing to remove on indexed.
        List.empty[EdgeWithIndex]
    }

    val edgesToInsert = {
      if (newPropsWithTs.isEmpty) List.empty[EdgeWithIndex]
      else {
        if (allPropsDeleted(newPropsWithTs)) {
          // all props is older than lastDeletedAt so nothing to insert on indexed.
          List.empty[EdgeWithIndex]
        } else {
          /** force operation on edge as insert */
          requestEdge.relatedEdges.flatMap { relEdge =>
            relEdge.edgesWithIndexValid(GraphUtil.defaultOpByte)
          }
        }
      }
    }

    val edgeInverted = if (newPropsWithTs.isEmpty) None else Some(requestEdge.toInvertedEdgeHashLike)

    val deleteMutations = edgesToDelete.flatMap(edge => edge.buildDeletesAsync())
    val insertMutations = edgesToInsert.flatMap(edge => edge.buildPutsAsync())
    val invertMutations = edgeInverted.map(e => List(e.buildPutAsync())).getOrElse(List.empty[PutRequest])
    val indexedEdgeMutations = deleteMutations ++ insertMutations
    val invertedEdgeMutations = invertMutations


    val update = EdgeUpdate(indexedEdgeMutations, invertedEdgeMutations, edgesToDelete, edgesToInsert, edgeInverted)

    //        logger.debug(s"UpdatedProps: ${newPropsWithTs}\n")
    //        logger.debug(s"EdgeUpdate: $update\n")
    //    logger.debug(s"$update")
    update
  }

  def buildUpsert(propsPairWithTs: PropsPairWithTs) = {
    var shouldReplace = false
    val (oldPropsWithTs, propsWithTs, requestTs, version) = propsPairWithTs
    val lastDeletedAt = oldPropsWithTs.get(LabelMeta.lastDeletedAt).map(v => v.ts).getOrElse(minTsVal)
    val existInOld = for ((k, oldValWithTs) <- oldPropsWithTs) yield {
      propsWithTs.get(k) match {
        case Some(newValWithTs) =>
          assert(oldValWithTs.ts >= lastDeletedAt)
          val v = if (oldValWithTs.ts >= newValWithTs.ts) oldValWithTs
          else {
            shouldReplace = true
            newValWithTs
          }
          Some(k -> v)

        case None =>
          assert(oldValWithTs.ts >= lastDeletedAt)
          if (oldValWithTs.ts >= requestTs || k < 0) Some(k -> oldValWithTs)
          else {
            shouldReplace = true
            None
          }
      }
    }
    val existInNew =
      for {
        (k, newValWithTs) <- propsWithTs if !oldPropsWithTs.contains(k) && newValWithTs.ts > lastDeletedAt
      } yield {
        shouldReplace = true
        Some(k -> newValWithTs)
      }

    ((existInOld.flatten ++ existInNew.flatten).toMap, shouldReplace)
  }

  def buildUpdate(propsPairWithTs: PropsPairWithTs) = {
    var shouldReplace = false
    val (oldPropsWithTs, propsWithTs, requestTs, version) = propsPairWithTs
    val lastDeletedAt = oldPropsWithTs.get(LabelMeta.lastDeletedAt).map(v => v.ts).getOrElse(minTsVal)
    val existInOld = for ((k, oldValWithTs) <- oldPropsWithTs) yield {
      propsWithTs.get(k) match {
        case Some(newValWithTs) =>
          assert(oldValWithTs.ts >= lastDeletedAt)
          val v = if (oldValWithTs.ts >= newValWithTs.ts) oldValWithTs
          else {
            shouldReplace = true
            newValWithTs
          }
          Some(k -> v)
        case None =>
          // important: update need to merge previous valid values.
          assert(oldValWithTs.ts >= lastDeletedAt)
          Some(k -> oldValWithTs)
      }
    }
    val existInNew = for {
      (k, newValWithTs) <- propsWithTs if !oldPropsWithTs.contains(k) && newValWithTs.ts > lastDeletedAt
    } yield {
        shouldReplace = true
        Some(k -> newValWithTs)
      }

    ((existInOld.flatten ++ existInNew.flatten).toMap, shouldReplace)
  }

  def buildIncrement(propsPairWithTs: PropsPairWithTs) = {
    var shouldReplace = false
    val (oldPropsWithTs, propsWithTs, requestTs, version) = propsPairWithTs
    val lastDeletedAt = oldPropsWithTs.get(LabelMeta.lastDeletedAt).map(v => v.ts).getOrElse(minTsVal)
    val existInOld = for ((k, oldValWithTs) <- oldPropsWithTs) yield {
      propsWithTs.get(k) match {
        case Some(newValWithTs) =>
          if (k == LabelMeta.timeStampSeq) {
            val v = if (oldValWithTs.ts >= newValWithTs.ts) oldValWithTs
            else {
              shouldReplace = true
              newValWithTs
            }
            Some(k -> v)
          } else {
            if (oldValWithTs.ts >= newValWithTs.ts) {
              Some(k -> oldValWithTs)
            } else {
              assert(oldValWithTs.ts < newValWithTs.ts && oldValWithTs.ts >= lastDeletedAt)
              shouldReplace = true
              // incr(t0), incr(t2), d(t1) => deleted
              Some(k -> InnerValLikeWithTs(oldValWithTs.innerVal + newValWithTs.innerVal, oldValWithTs.ts))
            }
          }

        case None =>
          assert(oldValWithTs.ts >= lastDeletedAt)
          Some(k -> oldValWithTs)
        //          if (oldValWithTs.ts >= lastDeletedAt) Some(k -> oldValWithTs) else None
      }
    }
    val existInNew = for {
      (k, newValWithTs) <- propsWithTs if !oldPropsWithTs.contains(k) && newValWithTs.ts > lastDeletedAt
    } yield {
        shouldReplace = true
        Some(k -> newValWithTs)
      }

    ((existInOld.flatten ++ existInNew.flatten).toMap, shouldReplace)
  }

  def buildDelete(propsPairWithTs: PropsPairWithTs) = {
    var shouldReplace = false
    val (oldPropsWithTs, propsWithTs, requestTs, version) = propsPairWithTs
    val lastDeletedAt = oldPropsWithTs.get(LabelMeta.lastDeletedAt) match {
      case Some(prevDeletedAt) =>
        if (prevDeletedAt.ts >= requestTs) prevDeletedAt.ts
        else {
          shouldReplace = true
          requestTs
        }
      case None => {
        shouldReplace = true
        requestTs
      }
    }
    val existInOld = for ((k, oldValWithTs) <- oldPropsWithTs) yield {
      if (k == LabelMeta.timeStampSeq) {
        if (oldValWithTs.ts >= requestTs) Some(k -> oldValWithTs)
        else {
          shouldReplace = true
          Some(k -> InnerValLikeWithTs.withLong(requestTs, requestTs, version))
        }
      } else {
        if (oldValWithTs.ts >= lastDeletedAt) Some(k -> oldValWithTs)
        else {
          shouldReplace = true
          None
        }
      }
    }
    val mustExistInNew = Map(LabelMeta.lastDeletedAt -> InnerValLikeWithTs.withLong(lastDeletedAt, lastDeletedAt, version))
    ((existInOld.flatten ++ mustExistInNew).toMap, shouldReplace)
  }

  def allPropsDeleted(props: Map[Byte, InnerValLikeWithTs]): Boolean = {
    if (!props.containsKey(LabelMeta.lastDeletedAt)) false
    else {
      val lastDeletedAt = props.get(LabelMeta.lastDeletedAt).get.ts
      for {
        (k, v) <- props if k != LabelMeta.lastDeletedAt
      } {
        if (v.ts > lastDeletedAt) return false
      }
      true
    }
  }


  def fromString(s: String): Option[Edge] = Graph.toEdge(s)


  def toEdges(kvs: Seq[KeyValue], queryParam: QueryParam,
              prevScore: Double = 1.0,
              isInnerCall: Boolean,
              parentEdges: Seq[EdgeWithScore]): Seq[(Edge, Double)] = {
    if (kvs.isEmpty) Seq.empty
    else {
      val first = kvs.head
      val firstKeyBytes = first.key()
      val edgeRowKeyLike = Option(EdgeRowKey.fromBytes(firstKeyBytes, 0, firstKeyBytes.length, queryParam.label.schemaVersion)._1)
      for {
        kv <- kvs
        edge <-
        if (queryParam.isSnapshotEdge) toSnapshotEdge(kv, queryParam, edgeRowKeyLike, isInnerCall, parentEdges)
        else toEdge(kv, queryParam, edgeRowKeyLike, parentEdges)
      } yield {
        //TODO: Refactor this.
        val currentScore =
          queryParam.scorePropagateOp match {
            case "plus" => edge.rank(queryParam.rank) + prevScore
            case _ => edge.rank(queryParam.rank) * prevScore
          }
        (edge, currentScore)
      }
    }
  }

  def toSnapshotEdge(kv: KeyValue, param: QueryParam, edgeRowKeyLike: Option[EdgeRowKeyLike] = None,
                     isInnerCall: Boolean,
                     parentEdges: Seq[EdgeWithScore]): Option[Edge] = {
    val version = kv.timestamp()
    val keyBytes = kv.key()
    val rowKey = edgeRowKeyLike.getOrElse {
      EdgeRowKey.fromBytes(keyBytes, 0, keyBytes.length, param.label.schemaVersion)._1
    }
    val srcVertexId = rowKey.srcVertexId
    val (tgtVertexId, props, op, ts, pendingEdgeOpt) = {

      val qBytes = kv.qualifier()
      val vBytes = kv.value()
      val vBytesLen = vBytes.length
      val (qualifier, _) = EdgeQualifierInverted.fromBytes(qBytes, 0, qBytes.length, param.label.schemaVersion)

      val (value, _) = EdgeValueInverted.fromBytes(vBytes, 0, vBytes.length, param.label.schemaVersion)
      val kvsMap = value.props.toMap
      val ts = kvsMap.get(LabelMeta.timeStampSeq) match {
        case None => version
        case Some(v) => BigDecimal(v.innerVal.toString).toLong
      }


      val pendingEdgePropsOffset = propsToKeyValuesWithTs(value.props).length + 1
      val pendingEdgeOpt = if (pendingEdgePropsOffset == vBytesLen) {
        None
      } else {
        var pos = pendingEdgePropsOffset
        val opByte = vBytes(pos)
        pos += 1
        val versionNum = Bytes.toLong(vBytes, pos, 8)
        pos += 8
        val (pendingEdgeProps, _) = bytesToKeyValuesWithTs(vBytes, pos, param.label.schemaVersion)
        Option(Edge(Vertex(srcVertexId, versionNum),
          Vertex(qualifier.tgtVertexId, versionNum), rowKey.labelWithDir, opByte, ts, versionNum, pendingEdgeProps.toMap))
      }
      (qualifier.tgtVertexId, kvsMap, value.op, ts, pendingEdgeOpt)
    }

    if (isInnerCall) {
      val edge =
        Edge(Vertex(srcVertexId, ts), Vertex(tgtVertexId, ts), rowKey.labelWithDir, op, ts, version, props, pendingEdgeOpt, parentEdges)
      val ret = if (param.where.map(_.filter(edge)).getOrElse(true)) {
        Some(edge)
      } else {
        None
      }

      ret
    } else {
      if (allPropsDeleted(props)) None
      else {
        val edge =
          Edge(Vertex(srcVertexId, ts), Vertex(tgtVertexId, ts), rowKey.labelWithDir, op, ts, version, props, pendingEdgeOpt, parentEdges)

        val ret = if (param.where.map(_.filter(edge)).getOrElse(true)) {
          logger.debug(s"fetchedEdge: $edge")
          Some(edge)
        } else {
          None
        }
        ret
      }
    }
  }

  def toEdge(kv: KeyValue, param: QueryParam, edgeRowKeyLike: Option[EdgeRowKeyLike] = None,
             parentEdges: Seq[EdgeWithScore]): Option[Edge] = {
    logger.debug(s"$param -> $kv")

    val version = kv.timestamp()
    val keyBytes = kv.key()
    val rowKey = edgeRowKeyLike.getOrElse {
      EdgeRowKey.fromBytes(keyBytes, 0, keyBytes.length, param.label.schemaVersion)._1
    }
    val srcVertexId = rowKey.srcVertexId
    var isDegree = false
    val (tgtVertexId, props, op, ts, pendingEdgeOpt) = {
      val kvQual = kv.qualifier()
      val vBytes = kv.value()
      if (kvQual.isEmpty) {
        /** degree */
        isDegree = true
        val degree = Bytes.toLong(kv.value())
        //FIXME: dirty hack. dummy target vertexId
        val ts = kv.timestamp()
        val dummyProps = Map(LabelMeta.degreeSeq -> InnerValLikeWithTs.withLong(degree, ts, param.label.schemaVersion))
        val tgtVertexId = VertexId(HBaseType.DEFAULT_COL_ID, InnerVal.withStr("0", param.label.schemaVersion))
        (tgtVertexId, dummyProps, GraphUtil.operations("insert"), ts, None)
      } else {
        /** edge */
        val (qualifier, _) = EdgeQualifier.fromBytes(kvQual, 0, kvQual.length, param.label.schemaVersion)

        val (value, _) = if (qualifier.op == GraphUtil.operations("incrementCount")) {
          val countVal = Bytes.toLong(vBytes)
          val dummyProps = Seq(LabelMeta.countSeq -> InnerVal.withLong(countVal, param.label.schemaVersion))
          (EdgeValue(dummyProps)(param.label.schemaVersion), 8)
        } else {
          EdgeValue.fromBytes(vBytes, 0, vBytes.length, param.label.schemaVersion)
        }

        val index = param.label.indicesMap.getOrElse(rowKey.labelOrderSeq, throw new RuntimeException(s"can`t find index sequence for $rowKey ${param.label}"))
        var kvsMap = Map.empty[Byte, InnerValLike]

        qualifier.propsKVs(index.metaSeqs).foreach { case (k, v) =>
          kvsMap += (k -> v)
        }

        value.props.foreach { case (k, v) =>
          kvsMap += (k -> v)
        }
        //          val kvs = qualifier.propsKVs(index.metaSeqs) ++ value.props
        //          val kvsMap = kvs.toMap
        val tgtVertexId = if (qualifier.tgtVertexId == null) {
          kvsMap.get(LabelMeta.toSeq) match {
            case None => qualifier.tgtVertexId
            case Some(vId) => TargetVertexId(HBaseType.DEFAULT_COL_ID, vId)
          }
        } else {
          qualifier.tgtVertexId
        }

        val ts = kvsMap.get(LabelMeta.timeStampSeq).map { v => BigDecimal(v.value.toString).toLong }.getOrElse(version)
        val mergedProps = kvsMap.map { case (k, innerVal) => k -> InnerValLikeWithTs(innerVal, ts) }
        (tgtVertexId, mergedProps, qualifier.op, ts, None)
        //            val ts = kv.timestamp()
        //            (srcVertexId, Map(LabelMeta.timeStampSeq -> InnerValLikeWithTs.withLong(ts, ts, param.label.schemaVersion)), 0.toByte, ts, None)
      }
    }
    if (!param.includeDegree && isDegree) {
      None
    } else {
      val edge =
      //        if (!param.label.isDirected && param.labelWithDir.dir == GraphUtil.directions("in")) {
      //          Edge(Vertex(srcVertexId, ts), Vertex(tgtVertexId, ts), rowKey.labelWithDir.updateDir(0), op, ts, version, props)
      //        } else {
      //      if (param.labelWithDir.dir == GraphUtil.directions("in")) {
      //        Edge(Vertex(tgtVertexId, ts), Vertex(srcVertexId, ts), rowKey.labelWithDir.dirToggled, op, ts, version, props, pendingEdgeOpt, parentEdges)
      //      } else {
        Edge(Vertex(srcVertexId, ts), Vertex(tgtVertexId, ts), rowKey.labelWithDir, op, ts, version, props, pendingEdgeOpt, parentEdges)
      //      }
      //      logger.error(s"$edge")
      Option(edge)

    }
  }

  //FIXME
  def buildIncrementDegreeBulk(srcVertexId: String, labelName: String, direction: String, degreeVal: Long) = {
    for {
      label <- Label.findByName(labelName)
      dir <- GraphUtil.toDir(direction)
      jsValue = Json.toJson(srcVertexId)
      innerVal <- jsValueToInnerVal(jsValue, label.srcColumnWithDir(dir).columnType, label.schemaVersion)
      vertexId = SourceVertexId(label.srcColumn.id.get, innerVal)
      vertex = Vertex(vertexId)
      labelWithDir = LabelWithDirection(label.id.get, GraphUtil.toDirection(direction))
      edge = Edge(vertex, vertex, labelWithDir)
    } yield {
      for {
        edgeWithIndex <- edge.edgesWithIndex
        incr <- edgeWithIndex.buildIncrementsBulk(degreeVal)
      } yield incr
    }
  }


  def incrementCounts(edges: Seq[Edge]): Future[Seq[(Boolean, Long)]] = {
    implicit val ex = Graph.executionContext

    val defers: Seq[Deferred[(Boolean, Long)]] = for {
      edge <- edges
    } yield {
        Try {
          val edgeWithIndex = edge.edgesWithIndex.head
          val countWithTs = edge.propsWithTs(LabelMeta.countSeq)
          val countVal = countWithTs.innerVal.toString().toLong
          val incr = edgeWithIndex.buildIncrementsCountAsync(countVal).head
          val request = incr.asInstanceOf[AtomicIncrementRequest]
          val client = Graph.getClient(edge.label.hbaseZkAddr)
          val defered = Graph.deferredCallbackWithFallback[java.lang.Long, (Boolean, Long)](client.bufferAtomicIncrement(request))({
            (resultCount: java.lang.Long) => (true, resultCount)
          }, (false, -1L))
          defered
        } match {
          case Success(r) => r
          case Failure(ex) => Deferred.fromResult((false, -1L))
        }
      }

    val grouped: Deferred[util.ArrayList[(Boolean, Long)]] = Deferred.groupInOrder(defers)
    Graph.deferredToFutureWithoutFallback(grouped).map(_.toSeq)
  }
}
