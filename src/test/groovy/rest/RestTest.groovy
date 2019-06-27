package rest

import rest.Credentials
import rest.RestService

class RestTest extends GroovyTestCase {
    def config
    Credentials cr
    void setUp() {

        cr=new Credentials("app_sc2sap","74179367bf1a8cfeb3b37fa50647d76b38c2fc02533ad6c287eb0f5de901b155","telefonica-ar1.test");
    }

    void testUsers() {
        def result =[]
        RestService rs= new RestService("https://api.etadirect.com/rest/ofscCore/v1/users",cr);
        result=rs.execute(100)
        assert result.size()==200
    }

    void testResources() {
        def result =[]
        RestService rs= new RestService("https://api.etadirect.com/rest/ofscCore/v1/resources",cr);
        result=rs.execute(100)
        assert result.size()==200
    }

    void testInventories() {
        def result =[]
        RestService rs= new RestService("https://api.etadirect.com/rest/ofscCore/v1/inventories",cr);
        result=rs.execute(100)
        assert result.size()==200
    }


    void testActivities() {
        def result =[]
        RestService rs= new RestService("https://api.etadirect.com/rest/ofscCore/v1/activities",cr);
        result=rs.execute(100)
        assert result.size()==200
    }


}