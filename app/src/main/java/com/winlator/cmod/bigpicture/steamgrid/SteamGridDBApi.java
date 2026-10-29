package com.winlator.cmod.bigpicture.steamgrid;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface SteamGridDBApi {

    @GET("search/autocomplete/{term}")
    Call<SteamGridSearchResponse> searchGame(
            @Header("Authorization") String authHeader,
            @Path("term") String searchTerm
    );

    // Updated method to fetch grids by game ID with filters
    @GET("grids/game/{gameId}")
    Call<SteamGridGridsResponse> getGridsByGameId(
            @Header("Authorization") String authToken,
            @Path("gameId") int gameId,
            @Query("styles") String styles,           // Example: "alternate"
            @Query("dimensions") String dimensions,   // Example: "600x900"
            @Query("types") String types              // Example: "static"
    );

    // Wide background art for the console home ("heroes", 1920x620).
    @GET("heroes/game/{gameId}")
    Call<SteamGridGridsResponse> getHeroesByGameId(
            @Header("Authorization") String authToken,
            @Path("gameId") int gameId,
            @Query("dimensions") String dimensions,
            @Query("types") String types
    );

    // Transparent title logos for the console home.
    @GET("logos/game/{gameId}")
    Call<SteamGridGridsResponse> getLogosByGameId(
            @Header("Authorization") String authToken,
            @Path("gameId") int gameId,
            @Query("types") String types
    );
}
