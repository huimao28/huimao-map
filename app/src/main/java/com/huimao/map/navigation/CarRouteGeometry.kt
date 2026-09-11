package com.huimao.map.navigation

import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)
data class RouteProjection(val point: GeoPoint, val segment: Int, val metersFromStart: Double, val distanceMeters: Double)
data class RouteArrow(val point: GeoPoint, val bearing: Double, val distanceFromVehicle: Double)

/** Pure geometry used by the car renderer; phone/Baidu remains navigation authority. */
object CarRouteGeometry {
    private const val EARTH = 6371000.0
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val p1= Math.toRadians(a.lat); val p2=Math.toRadians(b.lat); val dp=p2-p1; val dl=Math.toRadians(b.lon-a.lon)
        val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*EARTH*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    fun bearing(a: GeoPoint,b: GeoPoint): Double {
        val p1=Math.toRadians(a.lat); val p2=Math.toRadians(b.lat); val dl=Math.toRadians(b.lon-a.lon)
        return (Math.toDegrees(atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)))+360)%360
    }
    fun project(route: List<GeoPoint>, car: GeoPoint): RouteProjection? {
        if(route.isEmpty()) return null
        if(route.size==1) return RouteProjection(route[0],0,0.0,distance(route[0],car))
        var best:RouteProjection?=null; var travelled=0.0
        for(i in 0 until route.lastIndex){ val a=route[i]; val b=route[i+1]; val scale=cos(Math.toRadians((a.lat+b.lat)/2)).coerceAtLeast(.01)
            val x=(car.lon-a.lon)*scale; val y=car.lat-a.lat; val dx=(b.lon-a.lon)*scale; val dy=b.lat-a.lat; val d=dx*dx+dy*dy
            val t=if(d==0.0)0.0 else ((x*dx+y*dy)/d).coerceIn(0.0,1.0)
            val p=GeoPoint(a.lat+(b.lat-a.lat)*t,a.lon+(b.lon-a.lon)*t); val candidate=RouteProjection(p,i,travelled+distance(a,p),distance(p,car))
            if(best==null||candidate.distanceMeters<best!!.distanceMeters) best=candidate; travelled+=distance(a,b)
        }; return best
    }
    fun visibleRoute(route:List<GeoPoint>,car:GeoPoint):List<GeoPoint>{
        if(route.size<2)return route; val p=project(route,car)?:return route; val out=ArrayList<GeoPoint>(); out+=route[(p.segment-1).coerceAtLeast(0)]; out+=p.point
        for(i in p.segment+1..route.lastIndex)out+=route[i]; return out
    }
    fun pointAtDistance(route:List<GeoPoint>,target:Double):GeoPoint?{ var left=target.coerceAtLeast(0.0); if(route.isEmpty())return null
        for(i in 0 until route.lastIndex){val len=distance(route[i],route[i+1]);if(left<=len||len==0.0){val t=if(len==0.0)0.0 else left/len;return GeoPoint(route[i].lat+(route[i+1].lat-route[i].lat)*t,route[i].lon+(route[i+1].lon-route[i].lon)*t)};left-=len};return route.last() }
}
