package app


import rest.Credentials
import rest.RestService

def result =[]

Credentials cr=new Credentials("app_sc2sap","74179367bf1a8cfeb3b37fa50647d76b38c2fc02533ad6c287eb0f5de901b155","telefonica-ar1.test");
RestService rs= new RestService("https://api.etadirect.com/rest/ofscCore/v1/users",cr);
result=rs.execute(100)
result.collect{println it}
