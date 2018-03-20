package tukushan.es

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
case class ElasticsearchException(reason: String)

case class SearchResponse(hits: SearchHits)

case class SearchHits(total: Long, hits: Seq[SearchHit])

case class SearchHit(_index: String, _type: String, _id: String, _version: Long)

case class AcknowledgedResponse(acknowledged: Boolean)
