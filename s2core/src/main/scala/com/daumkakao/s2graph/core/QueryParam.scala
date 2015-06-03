package com.daumkakao.s2graph.core

//import com.daumkakao.s2graph.core.HBaseElement.{LabelWithDirection, InnerValWithTs, InnerVal, CompositeId}
//import com.daumkakao.s2graph.core.mysqls.{Label, LabelIndex, LabelMeta}
import com.daumkakao.s2graph.core.models.{Label, LabelIndex, LabelMeta}
import com.daumkakao.s2graph.core.parsers.Where
import com.daumkakao.s2graph.core.types.{LabelWithDirection, InnerVal, InnerValWithTs, CompositeId}
import scala.collection.mutable.ListBuffer
import org.apache.hadoop.hbase.util.Bytes
import GraphConstant._
import org.hbase.async.{ScanFilter, ColumnRangeFilter}
import types.EdgeType._

object Query {
  val initialScore = 1.0
  object DuplicatePolicy extends Enumeration {
    type DuplicatePolicy = Value
    val First, Sum, CountSum, Raw = Value
    def apply(policy: String): Value = {
      policy match {
        case "sum" => Query.DuplicatePolicy.Sum
        case "countSum" => Query.DuplicatePolicy.CountSum
        case "raw" => Query.DuplicatePolicy.Raw
        case _ => DuplicatePolicy.First
      }
    }
  }
}
case class Query(vertices: Seq[Vertex], steps: List[Step],
  unique: Boolean = true, removeCycle: Boolean = false) {

  import Query._
  lazy val startEdgesWithScore = (for {
    firstStep <- steps.headOption
  } yield {
    for (v <- vertices; qParam <- firstStep.queryParams) yield {
      val ts = System.currentTimeMillis()
      val srcVertex = Vertex(CompositeId(v.id.colId, v.innerId, isEdge = true, useHash = true), ts)
      (Edge(srcVertex, Vertex.emptyVertex,
        qParam.labelWithDir, GraphUtil.operations("insert"), ts, ts,
        Map.empty[Byte, InnerValWithTs]), Query.initialScore)
    }
  }).getOrElse(List.empty[(Edge, Double)])
}
case class VertexParam(vertices: Seq[Vertex]) {
  import Query._
  var filters: Option[Map[Byte, InnerVal]] = None
  def has(what: Option[Map[Byte, InnerVal]]): VertexParam = {
    what match {
      case None => this
      case Some(w) => has(w)
    }
  }
  def has(what: Map[Byte, InnerVal]): VertexParam = {
    this.filters = Some(what)
    this
  }

}
object RankParam {
  def apply(labelId: Int, keyAndWeights: Seq[(Byte, Double)]) = {
    new RankParam(labelId, keyAndWeights)
  }
}
class RankParam(val labelId: Int, var keySeqAndWeights: Seq[(Byte, Double)] = Seq.empty[(Byte, Double)]) { // empty => Count
  def defaultKey() = {
    this.keySeqAndWeights = List((LabelMeta.countSeq, 1.0))
    this
  }
  def singleKey(key: String) = {
    this.keySeqAndWeights =
      LabelMeta.findByName(labelId, key) match {
        case None => List.empty[(Byte, Double)]
        case Some(ktype) => List((ktype.seq, 1.0))
      }
    this
  }

  def multipleKey(keyAndWeights: Seq[(String, Double)]) = {
    this.keySeqAndWeights =
      for ((key, weight) <- keyAndWeights; row <- LabelMeta.findByName(labelId, key)) yield (row.seq, weight)
    this
  }
}


case class QueryParam(labelWithDir: LabelWithDirection) {

  import Query.DuplicatePolicy._
  import Query.DuplicatePolicy

  val label = Label.findById(labelWithDir.labelId)
  val defaultKey = LabelIndex.defaultSeq
  val fullKey = defaultKey

  var labelOrderSeq = fullKey
  var outputField: Option[Byte] = None
  //  var start = OrderProps.empty
  //  var end = OrderProps.empty
  var limit = 10
  var offset = 0
  var rank = new RankParam(labelWithDir.labelId, List(LabelMeta.countSeq -> 1))
  var isRowKeyOnly = false
  var duration: Option[(Long, Long)] = None

  //  var direction = 0
  //  var props = OrderProps.empty
  var isInverted: Boolean = false

  //  var filters = new FilterList(FilterList.Operator.MUST_PASS_ALL)
  //  val scanFilters = ListBuffer.empty[ScanFilter]
//  var filters = new FilterList(List.empty[ScanFilter], FilterList.Operator.MUST_PASS_ALL)
  var columnRangeFilter: ColumnRangeFilter = null
//  var columnPaginationFilter: ColumnPaginationFilter = null
  var exclude = false
  var include = false

  var hasFilters: Map[Byte, InnerVal] = Map.empty[Byte, InnerVal]
  //  var propsFilters: PropsFilter = PropsFilter()
  var where: Option[Where] = None
  var duplicatePolicy = DuplicatePolicy.First
  var rpcTimeoutInMillis = 100
  var maxAttempt = 2

  def isRowKeyOnly(isRowKeyOnly: Boolean): QueryParam = {
    this.isRowKeyOnly = isRowKeyOnly
    this
  }
  def isInverted(isInverted: Boolean): QueryParam = {
    this.isInverted = isInverted
    this
  }
  def labelOrderSeq(labelOrderSeq: Byte): QueryParam = {
    this.labelOrderSeq = labelOrderSeq
    this
  }
  def limit(offset: Int, limit: Int): QueryParam = {
    /** since degree info is located on first always */
    this.limit = if (offset == 0) limit + 1 else limit
    this.offset = offset
//    this.columnPaginationFilter = new ColumnPaginationFilter(this.limit, this.offset)
    this
  }
  def interval(fromTo: Option[(Seq[(Byte, InnerVal)], Seq[(Byte, InnerVal)])]): QueryParam = {
    fromTo match {
      case Some((from, to)) => interval(from, to)
      case _ => this
    }
  }
  def interval(from: Seq[(Byte, InnerVal)], to: Seq[(Byte, InnerVal)]): QueryParam = {
//    import HBaseElement._
    //    val len = label.orderTypes.size.toByte
    //    val len = label.extraIndicesMap(labelOrderSeq).sortKeyTypes.size.toByte
    //    Logger.error(s"indicesMap: ${label.indicesMap(labelOrderSeq)}")
    val len = label.indicesMap(labelOrderSeq).sortKeyTypes.size.toByte
    //    Logger.error(s"index length: $len")
    val minMetaByte = com.daumkakao.s2graph.core.types.InnerVal.minMetaByte
    val maxMetaByte = com.daumkakao.s2graph.core.types.InnerVal.maxMetaByte
    val toVal = Bytes.add(propsToBytes(to), Array.fill(1)(minMetaByte))
    val fromVal = Bytes.add(propsToBytes(from), Array.fill(1)(maxMetaByte))
    toVal(0) = len
    fromVal(0) = len
    val maxBytes = fromVal
    val minBytes = toVal
    val rangeFilter = new ColumnRangeFilter(minBytes, true, maxBytes, true)
    //    queryLogger.info(s"Interval: ${rangeFilter.getMinColumn().toList} ~ ${rangeFilter.getMaxColumn().toList}: ${Bytes.compareTo(minBytes, maxBytes)}")
    //    this.filters.(rangeFilter)
    this.columnRangeFilter = rangeFilter
    this
  }

  def duration(minMaxTs: Option[(Long, Long)]): QueryParam = {
    minMaxTs match {
      case Some((minTs, maxTs)) => duration(minTs, maxTs)
      case _ => this
    }
  }
  def duration(minTs: Long, maxTs: Long): QueryParam = {
    this.duration = Some((minTs, maxTs))
    this
  }

  def rank(r: RankParam): QueryParam = {
    this.rank = r
    this
  }
  def exclude(filterOut: Boolean): QueryParam = {
    this.exclude = filterOut
    this
  }

  def include(filterIn: Boolean): QueryParam = {
    this.include = filterIn
    this
  }

  def outputField(ofOpt: Option[Byte]): QueryParam = {
    this.outputField = ofOpt
    this
  }

  def has(hasFilters: Map[Byte, InnerVal]): QueryParam = {
    this.hasFilters = hasFilters
    this
  }

  def where(whereOpt: Option[Where]): QueryParam = {
    this.where = whereOpt
    this
  }

  def duplicatePolicy(policy: Option[DuplicatePolicy]): QueryParam = {
    this.duplicatePolicy = policy.getOrElse(DuplicatePolicy.First)
    this
  }

  def rpcTimeout(millis: Int): QueryParam = {
    this.rpcTimeoutInMillis = millis
    this
  }

  def maxAttempt(attempt: Int): QueryParam = {
    this.maxAttempt = attempt;
    this
  }

  override def toString(): String = {
    List(label.label, labelOrderSeq, offset, limit, rank, isRowKeyOnly,
      duration, isInverted, exclude, include, hasFilters, outputField).mkString("\t")
  }


  def buildGetRequest(srcVertex: Vertex) = {
    val rowKey = EdgeRowKey(srcVertex.id.updateUseHash(true), labelWithDir, labelOrderSeq, isInverted)
    val (minTs, maxTs) = duration.getOrElse((0L, Long.MaxValue))
    val client = Graph.getClient(label.hbaseZkAddr)
    val filters = ListBuffer.empty[ScanFilter]
    Graph.singleGet(label.hbaseTableName.getBytes, rowKey.bytes, edgeCf, offset, limit, minTs, maxTs, maxAttempt, rpcTimeoutInMillis, columnRangeFilter)
  }
}
case class Step(queryParams: List[QueryParam]) {
  lazy val excludes = queryParams.filter(qp => qp.exclude)
  lazy val includes = queryParams.filterNot(qp => qp.exclude)
  lazy val excludeIds = excludes.map(x => x.labelWithDir.labelId -> true).toMap
}