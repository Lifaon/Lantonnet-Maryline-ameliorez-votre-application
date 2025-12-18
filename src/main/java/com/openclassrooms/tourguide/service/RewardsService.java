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
import java.util.concurrent.atomic.AtomicInteger;
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

	// proximity in miles
    private static final int defaultProximityBuffer = 10;
	private static final Logger log = LoggerFactory.getLogger(RewardsService.class);
	private int proximityBuffer = defaultProximityBuffer;
	private static final int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	// Calculate Rewards
	private final ThreadPoolExecutor calcRewardsExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10000);
	private final List<CompletableFuture<Void>> calcRewardsFutures = new ArrayList<>();
	private final Lock calcRewardsLock = new ReentrantLock();
	private Long nbCompletedSinceLastWait = 0L;

	// Preloading
	private static final boolean enablePreloading = false;
	private final List<CompletableFuture<Void>> preloadingFutures = new ArrayList<>();
	private static class UserPoints {
		public Map<String, Integer> map = new HashMap<>();
		public ReadWriteLock lock = new ReentrantReadWriteLock();
	}
	private final Map<UUID, UserPoints> globalPointsMap = new HashMap<>();
	private final ReadWriteLock globalPointsLock = new ReentrantReadWriteLock();


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
		calcRewardsLock.lock();
		final CompletableFuture<Void> progression = CompletableFuture.supplyAsync(() -> {
			try {
				while (!calcRewardsExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
					long completed = calcRewardsExecutor.getCompletedTaskCount() - nbCompletedSinceLastWait;
					log.info("Reward calculations : Still running, {}% completed...",
							completed * 100 / calcRewardsFutures.size());
				}
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return null;
		});
		calcRewardsFutures.forEach(CompletableFuture::join);
		progression.cancel(true);
		nbCompletedSinceLastWait = calcRewardsExecutor.getCompletedTaskCount();
		calcRewardsLock.unlock();
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) <= attractionProximityRange;
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}

	private int getUnloadedPoints(UUID attrId, UUID userId) {
		return rewardsCentral.getAttractionRewardPoints(attrId, userId);
	}

	private final AtomicInteger nbPreloaded = new AtomicInteger(0);
	private final AtomicInteger nbSync = new AtomicInteger(0);

	public int getNbSync() {
		return nbSync.get();
	}
	public int getNbPreloaded() {
		return nbPreloaded.get();
	}

	public int getRewardPoints(Attraction attraction, User user) {
		UUID userId = user.getUserId();
		if (enablePreloading) {
			try {
				globalPointsLock.readLock().lock();
				if (globalPointsMap.containsKey(userId)) {
					UserPoints userPoints = globalPointsMap.get(userId);
					try {
						userPoints.lock.readLock().lock();
						if (userPoints.map.containsKey(attraction.attractionName)) {
							nbPreloaded.getAndIncrement();
							return userPoints.map.get(attraction.attractionName);
						}
					} finally {
						userPoints.lock.readLock().unlock();
					}
				}
			} finally {
				globalPointsLock.readLock().unlock();
			}
			nbSync.getAndIncrement();
		}
		return getUnloadedPoints(attraction.attractionId, userId);
	}

	private Void preloadUserRewardPoints(User user) {
		UUID userId = user.getUserId();
		UserPoints userPoints;
		try {
			globalPointsLock.writeLock().lock();
			if (!globalPointsMap.containsKey(userId)) {
				globalPointsMap.put(userId, new UserPoints());
			}
			userPoints = globalPointsMap.get(userId);
		} finally {
			globalPointsLock.writeLock().unlock();
		}
		List<Attraction> attractions = gpsUtil.getAttractions();

		for (Attraction attraction : attractions) {
			// Time-consuming
			int points = getUnloadedPoints(attraction.attractionId, userId);
			try {
				userPoints.lock.writeLock().lock();
				userPoints.map.put(attraction.attractionName, points);
			} finally {
				userPoints.lock.writeLock().unlock();
			}

		}
		return null;
	}

	public void preloadUsersRewardPoints(List<User> users) {
		if (!enablePreloading || users.isEmpty())
			return;
		ThreadPoolExecutor executor = null;
		// Processing
		try {
			executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.min(users.size(), 10000));
			for (User user : users) {
				preloadingFutures.add(CompletableFuture.supplyAsync(() -> preloadUserRewardPoints(user), executor));
			}
		} finally {
			if (executor != null)
				executor.shutdown();
		}
	}

	public void cancelPreloading() {
		preloadingFutures.forEach(f -> f.cancel(true));
		preloadingFutures.clear();
	}

	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
		return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
	}

}
