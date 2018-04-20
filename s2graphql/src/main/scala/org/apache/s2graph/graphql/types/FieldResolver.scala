package org.apache.s2graph.graphql.types

import org.apache.s2graph.core._
import org.apache.s2graph.core.mysqls._
import org.apache.s2graph.graphql.bind.AstHelper
import org.apache.s2graph.graphql.repository.GraphRepository
import sangria.schema._

object FieldResolver {

  def graphElement[A](name: String, cType: String, c: Context[GraphRepository, Any]): A = {
    c.value match {
      case v: S2VertexLike => name match {
        case "timestamp" => v.ts.asInstanceOf[A]
        case _ =>
          val innerVal = v.propertyValue(name).get
          JSONParser.innerValToAny(innerVal, cType).asInstanceOf[A]
      }
      case e: S2EdgeLike => name match {
        case "timestamp" => e.ts.asInstanceOf[A]
        case "direction" => e.getDirection().asInstanceOf[A]
        case _ =>
          val innerVal = e.propertyValue(name).get.innerVal
          JSONParser.innerValToAny(innerVal, cType).asInstanceOf[A]
      }
      case _ =>
        throw new RuntimeException(s"Error on resolving field: ${name}, ${cType}, ${c.value.getClass}")
    }
  }

  def label(label: Label, c: Context[GraphRepository, Any]): (S2VertexLike, QueryParam) = {
    val vertex = c.value.asInstanceOf[S2VertexLike]

    val dir = c.arg[String]("direction")
    val offset = c.arg[Int]("offset") + 1 // +1 for skip degree edge: currently not support
    val limit = c.arg[Int]("limit")
    val whereClauseOpt = c.argOpt[String]("filter")
    val where = c.ctx.parser.extractWhere(label, whereClauseOpt)

    val qp = QueryParam(
      labelName = label.label,
      direction = dir,
      offset = offset,
      limit = limit,
      whereRawOpt = whereClauseOpt,
      where = where
    )

    (vertex, qp)
  }

  def serviceColumnOnService(column: ServiceColumn, c: Context[GraphRepository, Any]): VertexQueryParam = {
    val prefix = s"${GlobalIndex.serviceField}:${column.service.serviceName} AND ${GlobalIndex.serviceColumnField}:${column.columnName}"

    val ids = c.argOpt[Any]("id").toSeq ++ c.argOpt[List[Any]]("ids").toList.flatten
    val offset = c.arg[Int]("offset")
    val limit = c.arg[Int]("limit")

    val vertices = ids.map(vid => c.ctx.toS2VertexLike(vid, column))
    val searchOpt = c.argOpt[String]("search").map { qs =>
      if (qs.trim.nonEmpty) s"(${prefix}) AND (${qs})"
      else prefix
    }

    val columnFields = column.metasInvMap.keySet
    val selectedFields = AstHelper.selectedFields(c.astFields)
    val canSkipFetch = selectedFields.forall(f => f == "id" || !columnFields(f))

    val vertexQueryParam = VertexQueryParam(offset, limit, searchOpt, vertices.map(_.id), !canSkipFetch)

    vertexQueryParam
  }

  def serviceColumnOnLabel(c: Context[GraphRepository, Any]): VertexQueryParam = {
    val edge = c.value.asInstanceOf[S2EdgeLike]

    val vertex = edge.tgtForVertex
    val column = vertex.serviceColumn

    val selectedFields = AstHelper.selectedFields(c.astFields)
    val columnFields = column.metasInvMap.keySet
    val canSkipFetch = selectedFields.forall(f => f == "id" || !columnFields(f))

    val vertexQueryParam = VertexQueryParam(0, 1, None, Seq(vertex.id), !canSkipFetch)

    vertexQueryParam
  }
}
