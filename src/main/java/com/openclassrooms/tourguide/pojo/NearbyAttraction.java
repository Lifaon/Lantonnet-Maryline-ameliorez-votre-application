package com.openclassrooms.tourguide.pojo;

import gpsUtil.location.Attraction;
import gpsUtil.location.Location;

public class NearbyAttraction {
    public final String name;
    public final Location location;
    public Location userLocation;
    public Double distance;
    public Integer rewardPoints;

    public NearbyAttraction(Attraction attraction) {
        name = attraction.attractionName;
        location = new Location(attraction.latitude, attraction.longitude);
    }
}