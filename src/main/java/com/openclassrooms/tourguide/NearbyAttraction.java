package com.openclassrooms.tourguide;

import gpsUtil.location.Attraction;
import gpsUtil.location.Location;

public class NearbyAttraction {
	public final String name;		// Name of attraction
	public final Location location; // The attraction lat/long
	public Location userLocation; 	// The user's location lat/long
	public Double distance; 		// The distance in miles between the user's location and the attraction
	public Integer rewardPoints;	// The reward points for visiting the attraction

	public NearbyAttraction(Attraction attraction) {
		name = attraction.attractionName;
		location = new Location(attraction.latitude, attraction.longitude);
	}
}