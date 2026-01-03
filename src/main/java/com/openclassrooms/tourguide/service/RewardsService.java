package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	private Logger logger = LoggerFactory.getLogger(RewardsService.class);

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	// Calculate Rewards
	private final ThreadPoolExecutor calcRewardsExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1000);
	private final List<CompletableFuture<Void>> calcRewardsFutures = new ArrayList<>();
	private final Lock calcRewardsLock = new ReentrantLock();
	private Long nbCompletedSinceLastWait = 0L;	// Used for completion percentage logging

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}
	
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	// Should be called inside a thread
	private Void calculateRewardsSync(User user) {
		List<VisitedLocation> userLocations = user.getVisitedLocations();
		List<Attraction> attractions = gpsUtil.getAttractions();
		
		for(VisitedLocation visitedLocation : userLocations) {
			for(Attraction attraction : attractions) {
				if(user.notYetRewardedFor(attraction)) {
					if(nearAttraction(visitedLocation, attraction)) {
						user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
					}
				}
			}
		}
		return null;
	}

	public void calculateRewards(User user) {
		calcRewardsLock.lock();
		calcRewardsFutures.add(CompletableFuture.supplyAsync(() -> calculateRewardsSync(user), calcRewardsExecutor));
		calcRewardsLock.unlock();
	}

	public void waitRewardCalculations() {
		// Block queue
		calcRewardsLock.lock();
		// Only for logging
		final CompletableFuture<Void> progression = CompletableFuture.supplyAsync(() -> {
			try {
				while (!calcRewardsExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
					long completed = calcRewardsExecutor.getCompletedTaskCount() - nbCompletedSinceLastWait;
					logger.debug("Reward calculations : Still running, {}% completed...",
							completed * 100 / calcRewardsFutures.size());
				}
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return null;
		});
		// Wait for ALL tasks to complete
		calcRewardsFutures.forEach(CompletableFuture::join);
		progression.cancel(true);
		nbCompletedSinceLastWait = calcRewardsExecutor.getCompletedTaskCount();
		// Unblock queue
		calcRewardsLock.unlock();
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) > proximityBuffer ? false : true;
	}

	public int getRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}

	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
		double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
		return statuteMiles;
	}

}
