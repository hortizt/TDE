package app


import rest.Credentials
import users.UsersService

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.TEXT

Credentials cr=new Credentials("app_sc2sap","74179367bf1a8cfeb3b37fa50647d76b38c2fc02533ad6c287eb0f5de901b155","telefonica-ar1.test");
UsersService rs= new UsersService("https://api.etadirect.com/rest/ofscCore/v1/users",cr);





// initialize a new builder and give a default URL
def http = new HTTPBuilder('https://api.etadirect.com')

http.request(GET,TEXT) { req ->
    uri.path = '/rest/ofscCore/v1/users/' // overrides any path in the default URL
    headers.'Authorization' =rs.authorizationHeader()

    response.success = { resp, reader ->
        assert resp.status == 200
        println "My response handler got response: ${resp.statusLine}"
        println "Response length: ${resp.headers.'Content-Length'}"
        System.out << reader // print response reader
    }

    // called only for a 404 (not found) status code:
    response.'404' = { resp ->
        println 'Not found'
    }
}