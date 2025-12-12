package com.openclassrooms.tourguide;

import java.util.ArrayList;
import java.util.List;

import com.openclassrooms.tourguide.service.RewardsService;
import gpsUtil.location.Location;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gpsUtil.location.Attraction;
import gpsUtil.location.VisitedLocation;

import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import tripPricer.Provider;

@RestController
public class TourGuideController {

	@Autowired
	TourGuideService tourGuideService;

    @Autowired
    RewardsService rewardsService;
	
    @RequestMapping("/")
    public String index() {
        return "Greetings from TourGuide!";
    }
    
    @RequestMapping("/getLocation") 
    public VisitedLocation getLocation(@RequestParam String userName) {
    	return tourGuideService.getUserLocation(getUser(userName));
    }

	public static class NearbyAttraction {
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

 	//  Gets the closest five tourist attractions to the user - no matter how far away they are
    @RequestMapping("/getNearbyAttractions") 
    public List<NearbyAttraction> getNearbyAttractions(@RequestParam String userName) {
        List<NearbyAttraction> nearbyAttractions = new ArrayList<>();
        User user = getUser(userName);
    	VisitedLocation visitedLocation = tourGuideService.getUserLocation(user);
        for (Attraction attraction : tourGuideService.getNearByAttractions(visitedLocation)) {
            NearbyAttraction nearbyAttraction = new NearbyAttraction(attraction);
            nearbyAttraction.userLocation = visitedLocation.location;
            nearbyAttraction.distance = rewardsService.getDistance(attraction, visitedLocation.location);
            nearbyAttraction.rewardPoints = rewardsService.getRewardPoints(attraction, user);
            nearbyAttractions.add(nearbyAttraction);
        }
        return nearbyAttractions;
    }
    
    @RequestMapping("/getRewards") 
    public List<UserReward> getRewards(@RequestParam String userName) {
    	return tourGuideService.getUserRewards(getUser(userName));
    }
       
    @RequestMapping("/getTripDeals")
    public List<Provider> getTripDeals(@RequestParam String userName) {
    	return tourGuideService.getTripDeals(getUser(userName));
    }
    
    private User getUser(String userName) {
    	return tourGuideService.getUser(userName);
    }
   

}