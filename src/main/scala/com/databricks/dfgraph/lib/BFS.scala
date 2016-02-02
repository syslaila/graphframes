/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.dfgraph.lib

import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, DataFrame, Row, SQLHelpers}

import com.databricks.dfgraph.DFGraph
import com.databricks.dfgraph.DFGraph.nestAsCol


object BFS extends Logging {

  /**
   * Breadth-first search (BFS)
   *
   * This method returns a DataFrame of valid shortest paths from vertices matching `fromExpr`
   * to vertices matching `toExpr`.  If multiple paths are valid and have the same length,
   * the DataFrame will return one Row for each path.  If no paths are valid, the DataFrame will
   * be empty.
   * Note: "Shortest" means globally shortest path.  I.e., if the shortest path between two vertices
   * matching `fromExpr` and `toExpr` is length 5 (edges) but no path is shorter than 5, then all
   * paths returned by BFS will have length 5.
   *
   * The returned DataFrame will have the following columns:
   *  - from: start vertex of path
   *  - e[i]: edge i in the path, indexed from 0
   *  - v[i]: intermediate vertex i in the path, indexed from 1
   *  - to: end vertex of path
   * Each of these columns is a StructType whose fields are the same as the columns of
   * [[DFGraph.vertices]] or [[DFGraph.edges]].
   *
   * For example, suppose we have a graph g.  Say the vertices DataFrame of g has columns "id" and
   * "job", and the edges DataFrame of g has columns "src", "dst", and "relation".
   * {{{
   *   // Search from vertex "Joe" to find the closet vertices with attribute job = CEO.
   *   g.bfs(col("id") === "Joe", col("job") === "CEO").run()
   * }}}
   * If we found a path of 3 edges, each row would have columns:
   * {{{from | e0 | v1 | e1 | v2 | e2 | to}}}
   * In the above row, each vertex column (from, v1, v2, to) would have fields "id" and "job"
   * (just like g.vertices).
   * Each edge column (e0, e1, e2) would have fields "src", "dst", and "relation".
   *
   * If there are ties, then each of the equal paths will be returned as a separate Row.
   *
   * If one or more vertices match both the from and to conditions, then there is a 0-hop path.
   * The returned DataFrame will have the "from" and "to" columns (as above); however,
   * the "from" and "to" columns will be exactly the same.  There will be one row for each vertex
   * in [[DFGraph.vertices]] matching both `fromExpr` and `toExpr`.
   *
   * @param from  Spark SQL expression specifying valid starting vertices for the BFS.
   *                  This condition will be matched against each vertex's id or attributes.
   *                  To start from a specific vertex, this could be "id = [start vertex id]".
   *                  To start from multiple valid vertices, this can operate on vertex attributes.
   * @param to  Spark SQL expression specifying valid target vertices for the BFS.
   *                This condition will be matched against each vertex's id or attributes.
   * @param maxPathLength  Limit on the length of paths.  If no valid paths of length
   *                       <= maxPathLength are found, then the BFS is terminated.
   *                       (default = 10)
   * @param edgeFilter  Spark SQL expression specifying edges which may be used in the search.
   *                    This allows the user to disallow crossing certain edges.  Such filters
   *                    can be applied post-hoc after BFS, run specifying the filter here is more
   *                    efficient.
   * @return  DataFrame of valid shortest paths found in the BFS
   */
  private[dfgraph] def run(
      g: DFGraph,
      from: Column,
      to: Column,
      maxPathLength: Int,
      edgeFilter: Option[Column]): DataFrame = {
    val fromDF = g.vertices.filter(from)
    val toDF = g.vertices.filter(to)
    if (fromDF.take(1).isEmpty || toDF.take(1).isEmpty) {
      // Return empty DataFrame
      return g.sqlContext.createDataFrame(
        g.sqlContext.sparkContext.parallelize(Seq.empty[Row]),
        g.vertices.schema)
    }

    val fromEqualsToDF = fromDF.filter(to)
    if (fromEqualsToDF.take(1).nonEmpty) {
      // from == to, so return matching vertices
      return fromEqualsToDF.select(
        nestAsCol(fromEqualsToDF, "from"), nestAsCol(fromEqualsToDF, "to"))
    }

    // We handled edge cases above, so now we do BFS.

    // Edges a->b, to be reused for each iteration
    val a2b: DataFrame = {
      val a2b = g.find("(a)-[e]->(b)")
      edgeFilter match {
        case Some(ef) =>
          val efExpr = applyExprToCol(ef, "e")
          a2b.filter(efExpr)
        case None =>
          a2b
      }
    }

    // We will always apply fromExpr to column "a"
    val fromAExpr = applyExprToCol(from, "a")

    // DataFrame of current search paths
    var paths: DataFrame = null

    var iter = 0
    var foundPath = false
    while (iter < maxPathLength && !foundPath) {
      val nextVertex = s"v${iter + 1}"
      val nextEdge = s"e$iter"
      // Take another step
      if (iter == 0) {
        // Note: We could avoid this special case by initializing paths with just 1 "from" column,
        // but that would create a longer lineage for the result DataFrame.
        paths = a2b.filter(fromAExpr)
          .filter(col("a.id") !== col("b.id"))  // remove self-loops
          .withColumnRenamed("a", "from").withColumnRenamed("e", nextEdge)
          .withColumnRenamed("b", nextVertex)
      } else {
        val prevVertex = s"v$iter"
        val nextLinks = a2b.withColumnRenamed("a", prevVertex).withColumnRenamed("e", nextEdge)
          .withColumnRenamed("b", nextVertex)
        paths = paths.join(nextLinks, paths(prevVertex + ".id") === nextLinks(prevVertex + ".id"))
          .drop(paths(prevVertex))
        // Make sure we are not backtracking within each path.
        // TODO: Avoid crossing paths; i.e., touch each vertex at most once.
        val previousVertexChecks = Range(1, iter + 1)
          .map(i => paths(s"v$i.id") !== paths(nextVertex + ".id"))
          .foldLeft(paths(s"from.id") !== paths(nextVertex + ".id"))((c1, c2) => c1 && c2)
        paths = paths.filter(previousVertexChecks)
      }
      // Check if done by applying toExpr to column nextVertex
      val toVExpr = applyExprToCol(to, nextVertex)
      val foundPathDF = paths.filter(toVExpr)
      if (foundPathDF.take(1).nonEmpty) {
        // Found path
        paths = foundPathDF.withColumnRenamed(nextVertex, "to")
        foundPath = true
      }
      iter += 1
    }
    if (foundPath) {
      logInfo(s"DFGraph.bfs found path of length $iter.")
      paths
    } else {
      logInfo(s"DFGraph.bfs failed to find a path of length <= $maxPathLength.")
      // Return empty DataFrame
      g.sqlContext.createDataFrame(
        g.sqlContext.sparkContext.parallelize(Seq.empty[Row]),
        g.vertices.schema)
    }
  }

  class Builder private[dfgraph] (graph: DFGraph, fromExpr: Column, toExpr: Column)
    extends Arguments {

    private var maxPathLength: Int = 10

    def setMaxPathLength(value: Int): this.type = {
      require(value >= 0, s"BFS maxPathLength must be >= 0, but was set to $value")
      maxPathLength = value
      this
    }

    private var edgeFilter: Option[Column] = None

    def setEdgeFilter(value: Column): this.type = {
      edgeFilter = Some(value)
      this
    }

    // def setEdgeFilter(value: String): this.type = setEdgeFilter(expr(value))

    def run(): DataFrame = {
      BFS.run(graph, fromExpr, toExpr, maxPathLength, edgeFilter)
    }
  }

  /**
   * Apply the given SQL expression (such as `id = 3`) to the field in a column,
   * rather than to the column itself.
   * @param expr  SQL expression, such as `id = 3`
   * @param colName  Column name, such as `myVertex`
   * @return  SQL expression applied to the column fields, such as `myVertex.id = 3`
   */
  private def applyExprToCol(expr: Column, colName: String) = {
    new Column(SQLHelpers.getExpr(expr).transform {
      case UnresolvedAttribute(nameParts) => UnresolvedAttribute(colName +: nameParts)
    })
  }
}