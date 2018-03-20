package tukushan.es

import com.ning.http.client.Response
import dispatch.{FunctionHandler, Req}

// see RequestHandlerTupleBuilder
class DispatchReqHandlers(req: Req) {

  // OK handler that returns more info (URL & response body) in exception if status is non 2xx - helps debug errors
  def OK[T](f: Response => T) =
    (req.toRequest, new FunctionHandler(r => {
      if (r.getStatusCode / 100 == 2)
        f(r)
      else
        throw new Exception(s"Unexpected response status: ${r.getStatusCode} ${r.getStatusText} for ${req.toRequest.getMethod} ${r.getUri}\n:${r.getResponseBody}")
    }))
}
