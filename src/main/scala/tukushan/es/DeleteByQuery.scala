package tukushan.es

import dispatch.Defaults._
import dispatch._
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.joda.time.Duration
import org.json4s.jackson.Serialization

import scala.concurrent.Future
import scala.util.{Failure, Success}

case class ScrollResponse(_scroll_id: String, hits: SearchHits)

case class ScrollRequestBody(scroll: String, scroll_id: String)

case class ScrollDeleteRequestBody(scroll_id: Seq[String])

case class BulkDeleteResponse(items: Seq[BulkDeleteItem])

case class BulkDeleteItem(delete: DeleteResponse)

case class DeleteResponse(_id: String, found: Option[Boolean], error: Option[ElasticsearchException])


/*
 found = The number of documents matching the query for the given index.
 deleted = The number of documents successfully deleted for the given index.
 missing = Missing documents were present when the original query was run, but have already been deleted by another process.
 failed = A document may fail to be deleted if it has been updated to a new version by another process, or if the shard
  containing the document has gone missing due to hardware failure, for example.
 */
case class DeleteByQueryResponse(found: Long, deleted: Long, missing: Long, errors: Seq[ElasticsearchException]) {
  def failed: Long = errors.size

  def add(resp: BulkDeleteResponse) = {
    this.copy(deleted = this.deleted + resp.items.count(_.delete.found.exists(identity)),
      missing = this.missing + resp.items.count(_.delete.found.exists(!_)),
      errors = this.errors ++ resp.items.flatMap(_.delete.error))
  }
}

/*
 This mimics the elastic delete by query plugin, see

 https://www.elastic.co/guide/en/elasticsearch/plugins/current/delete-by-query-usage.html
 https://github.com/elastic/elasticsearch/blob/2.3/plugins/delete-by-query/src/main/java/org/elasticsearch/action/deletebyquery/TransportDeleteByQueryAction.java

 Unlike the plugin, it does not have timeout functionality, and the response does not include shard failures or a
 breakdown of results by indices.

 It's written using Futures but is actually sequential, nothing happens in parallel
 */
trait DeleteByQuery extends Logging with JSONUtil {

  val baseUrl: String
  val index: String
  val docType: String
  val http: Http

  implicit def implyRequestHandlerTuple(builder: Req): DispatchReqHandlers = new DispatchReqHandlers(builder)

  // size = number of hits per shared to be returned for each scroll
  // see https://www.elastic.co/guide/en/elasticsearch/client/java-api/current/java-search-scrolling.html
  def delete(deleteQuery: QueryBuilder, scrollSize: Int, scrollTimeout: Duration): Future[DeleteByQueryResponse] = {
    val firstScroll = executeScan(deleteQuery, scrollSize, scrollTimeout)
    for {
      firstScrollResponse <- firstScroll
      result <- deleteHits(DeleteByQueryResponse(firstScrollResponse.hits.total, 0, 0, Seq.empty))(firstScrollResponse._scroll_id, scrollTimeout, firstScroll)
    } yield result
  }

  private def deleteHits(acc: DeleteByQueryResponse)
                        (scrollId: String, scrollTimeout: Duration, scrollResponse: Future[ScrollResponse]): Future[DeleteByQueryResponse] = {
    scrollResponse.flatMap { resp =>
      val docs = resp.hits.hits

      // TODO
      // addShardFailures(scrollResponse.getShardFailures)

      log.trace("scroll request [{}] executed: [{}] document(s) returned", scrollId, docs.length)

      if (docs.isEmpty) {
        log.trace("scrolling documents terminated")
        clearScroll(scrollId).map(_ => acc)
      } else {
        val nextScrollId = resp._scroll_id

        for {
          deleted <- bulkDelete(docs) recover { case e => clearScroll(scrollId); throw e }
          nextScroll = executeScroll(nextScrollId, scrollTimeout) recover { case e => clearScroll(nextScrollId); throw e }
          nextDelete <- deleteHits(acc.add(deleted))(nextScrollId, scrollTimeout, nextScroll)
        } yield nextDelete

      }
    }
  }

  private def executeScan(query: QueryBuilder, scrollSize: Int, scrollTimeout: Duration): Future[ScrollResponse] = {
    val source = SearchSourceBuilder.searchSource()
      .sort("_doc")     // important for performance
      .fetchSource(false)
      .version(true)
      .size(scrollSize)
      .query(query)

    val scanReq: Req = dispatch.url(s"$baseUrl/$index/$docType/_search?scroll=${scrollTimeout.getMillis}ms").GET.setBody(source.toString)
    log.trace(s"built scan request ${scanReq.url} with query\n$query")

    http(scanReq OK as.String).map { str =>
      val resp = fromJSON[ScrollResponse](str)
      log.trace("first request executed: found [{}] document(s) to delete", resp.hits.total)
      resp
    } recover {
      case e => throw new Exception("unable to execute the initial scan request of delete by query", e)
    }
  }

  private[es] def bulkDelete(docs: Seq[SearchHit]): Future[BulkDeleteResponse] = {
    val body = buildDeletePayload(docs)
    val bulkDeleteReq = dispatch.url(s"$baseUrl/$index/$docType/_bulk").POST.setBody(body)
    log.trace("built bulk request with [{}] deletions", docs.size)

    http(bulkDeleteReq OK as.String).map { str =>
      val resp = fromJSON[BulkDeleteResponse](str)
      resp.items.flatMap(_.delete.error).foreach { error =>
        log.warn("bulk delete item failure:" + error.reason)
      }
      resp
    } recover {
      case e => throw new Exception("execution of bulk delete failed", e)
    }
  }

  private[es] def buildDeletePayload(docs: Seq[SearchHit]): String = {
    docs.map { doc =>
      s"""{ "delete" : ${Serialization.write(doc)} }\n"""
    }.mkString("")
  }

  //ref https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-scroll.html
  private def executeScroll(scrollId: String, scrollTimeout: Duration): dispatch.Future[ScrollResponse] = {
    val body = ScrollRequestBody(scroll = scrollTimeout.getMillis.toString + "ms", scroll_id = scrollId)
    val scrollReq = dispatch.url(s"$baseUrl/_search/scroll").GET.setBody(toJSON(body))
    log.trace("built scroll request [{}]", scrollId)

    http(scrollReq OK as.String).map { str =>
      fromJSON[ScrollResponse](str)
    } recover {
      case cause => throw new Exception(s"unable to execute scroll request [$scrollId]", cause)
    }

  }

  private def clearScroll(scrollId: String): Future[Unit] = {
    val body = ScrollDeleteRequestBody(scroll_id = Seq(scrollId))
    val req = dispatch.url(s"$baseUrl/_search/scroll").DELETE.setBody(toJSON(body))

    http(req OK as.String).andThen {
      case Success(v) => log.trace("scroll id [{}] cleared", scrollId)
      case Failure(t) => log.warn(s"unable to clear scroll id [$scrollId]: ${t.getMessage}")
    }.map(_ => Unit)

  }

}

