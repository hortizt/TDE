package rest

import groovyx.net.http.HTTPBuilder


import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.GET

class RestService extends Rest{
    def host, path
    def limit=100, ofsset
    RestService(url,credentials) {
        super(url,credentials)
        def urlaux=new URL(url)
        host="${urlaux.protocol}://${urlaux.host}"
        path=urlaux.path
    }

List execute(limMax){
    ofsset=0
    def totalResults,leidos
    def http = new HTTPBuilder(host)
    def result =[]
    http.request(GET,JSON) { req ->
        uri.path = path
        headers.'Authorization' =authorizationHeader()
        uri.query = [ limit:limit, offset: 0 ]

        response.success = { resp, reader ->
            assert resp.status == 200
            result.addAll(reader.items)
            totalResults=reader.totalResults
        }

        response.'404' = { resp ->
            println 'Not found'
        }
    }
    leidos=limit

    while (leidos <= totalResults && (limMax!=null)?leidos<=limMax:true){
        http.request(GET,JSON) { req ->
            uri.path = path
            headers.'Authorization' =authorizationHeader()
            uri.query = [ limit:limit, offset: leidos ]

            response.success = { resp, reader ->
                assert resp.status == 200
                result.addAll(reader.items)
            }

            response.'404' = { resp ->
                println 'Not found'
            }
        }
        leidos=leidos+limit
        println "Leidos:${leidos}"
    }
    return result
}

}
