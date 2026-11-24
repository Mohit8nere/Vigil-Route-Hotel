package com.vigil.android.core.di.api

import com.vigil.android.core.di.model.LoginRequest
import com.vigil.android.core.di.model.LoginResponse
import com.vigil.android.core.di.model.dashboard_model.OfficerRouteMasterRequest
import com.vigil.android.core.di.model.dashboard_model.OfficerRoutesDetailsResponseDataModel
import com.vigil.android.core.di.model.dashboard_model.RouteLocationLogsResponseModel
import com.vigil.android.core.di.model.dashboard_model.RouteStopsRequest
import com.vigil.android.core.di.model.dashboard_model.RouteVerifiedDetailsResponseModel
import com.vigil.android.core.di.model.route_create_model.CreateRouteRequestModel
import com.vigil.android.core.di.model.route_create_model.CreateRouteResponseModel
import com.vigil.android.core.di.model.route_create_model.GetRoutesResponseModel
import com.vigil.android.core.di.model.route_create_model.UpdateRouteRequestModel
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST(ApiNames.AUTH_API)
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST(ApiNames.OFFICERS_ROUTE_DETAILS_LIST_API)
    suspend fun getOfficersList(@Body request: OfficerRouteMasterRequest): Response<OfficerRoutesDetailsResponseDataModel>

    @GET(ApiNames.ROUTE_LOCATION_LOGS_API)
    suspend fun routeLocationLogsList(
        @Query("officer_id") officerId: String,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("officer_route_join_id") dutyId: String
    ): Response<RouteLocationLogsResponseModel>

    @POST(ApiNames.ROUTE_VERIFIED_DETAILS_API)
    suspend fun routeVerifiedDetailsList(
        @Body request: RouteStopsRequest
    ): Response<RouteVerifiedDetailsResponseModel>


    @POST(ApiNames.ROUTE_API)
    suspend fun createRouteApi(
        @Body request: CreateRouteRequestModel
    ): CreateRouteResponseModel

    @PATCH("api/v1/routes/{route_id}/")
    suspend fun updateRouteApi(
        @Path("route_id") routeId: Long,
        @Body request: UpdateRouteRequestModel
    ): CreateRouteResponseModel

    @GET(ApiNames.GET_ROUTE_BY_STATION_API)
    suspend fun getRoutesByStationApi(
        @Query("district_id") districtId: Int,
        @Query("station_id") stationId: Int
    ): GetRoutesResponseModel

}