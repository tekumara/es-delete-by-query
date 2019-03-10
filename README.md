# es-delete-by-query

Elasticsearch 2.x doesn't come with built-in delete by query functionality. 

This mimics the elastic delete by query plugin via HTTP, see

https://www.elastic.co/guide/en/elasticsearch/plugins/current/delete-by-query-usage.html
https://github.com/elastic/elasticsearch/blob/2.3/plugins/delete-by-query/src/main/java/org/elasticsearch/action/deletebyquery/TransportDeleteByQueryAction.java

Unlike the plugin, it does not have timeout functionality, and the response does not include shard failures or a breakdown of results by indices.

It's written using [dispatch](https://github.com/dispatch/reboot) Futures but is actually sequential, nothing happens in parallel.

A large number of delete operations hurts concurrent read performance. Consider building a new index and flipping an alias instead, particularly if a delete will touch every document.
