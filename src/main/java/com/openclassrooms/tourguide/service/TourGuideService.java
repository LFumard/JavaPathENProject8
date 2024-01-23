package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import tripPricer.Provider;
import tripPricer.TripPricer;



@Service
public class TourGuideService {
	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;
	private ExecutorService executorService = Executors.newFixedThreadPool(1000);
	//int numThreads = Runtime.getRuntime().availableProcessors();
	//private ExecutorService executorService = Executors.newFixedThreadPool(numThreads * 2);

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		
		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
		//VisitedLocation visitedLocation = user.getLastVisitedLocation();
		//return visitedLocation;
		return user.getLastVisitedLocation();
	}

	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	public List<User> getAllUsers() {
		return internalUserMap.values().stream().collect(Collectors.toList());
	}

	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	public List<Provider> getTripDeals(User user) {
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	public void trackUserLocation(User user) {
		submitLocation(user);
		//VisitedLocation visitedLocation;
		//VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		//user.addToVisitedLocations(visitedLocation);
		//addAsyncUserToVisitedLocations(user);
		//rewardsService.calculateRewards(user);
		//return visitedLocation;
		//return null;
	}

	@Async
	public void submitLocation(User user) {
		//static AtomicReference<VisitedLocation> atomicReference;

			CompletableFuture.supplyAsync(() -> {
				//return gpsUtil.getUserLocation(user.getUserId());
						try {
							return getUserLocationMultiThread(user.getUserId());
						} catch (ExecutionException | InterruptedException e) {
							throw new RuntimeException(e);
						}
					}, executorService)
					.thenAccept(visitedLocation -> {
						completeLocation(user, visitedLocation);
					});
		//return user.getLastVisitedLocation();
		//return null;
	}

	private VisitedLocation getUserLocationMultiThread(UUID uuid) throws ExecutionException, InterruptedException {
		//nbThread++;
		//System.out.println("NbThread new: " + nbThread);
		//return gpsUtil.getUserLocation(uuid);
		return gpsUtil.getUserLocation(uuid);
	}
	private void completeLocation(User user, VisitedLocation visitedLocation) {
		user.addToVisitedLocations(visitedLocation);
		rewardsService.calculateRewards(user);
		//nbThread--;
		//System.out.println(" --> NbThread finalize: " + nbThread);
		//tracker.finalizeTrack(user);
		//return visitedLocation;
	}
	/*private CompletableFuture <VisitedLocation> addAsyncUserToVisitedLocations(User user) {
		CompletableFuture.supplyAsync(() -> {
			return gpsUtil.getUserLocation(user.getUserId());
		}, executorService).thenAccept(visitedLocation -> { user.addToVisitedLocations(visitedLocation);});
		//return visitedLocation;
		//return visitedLocation;
	}*/

	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		List<Attraction> nearbyAttractions = new ArrayList<>();

		Map<Double, Integer> mapdistance = new TreeMap<Double, Integer>();
		List<Attraction> allAtractions = gpsUtil.getAttractions();

		for (int i = 0; i < allAtractions.size(); i++) {
			mapdistance.put(rewardsService.getDistance(allAtractions.get(i), visitedLocation.location), i);
		}

		/*Set set = mapdistance.entrySet();
		Iterator itr = set.iterator();
		while (itr.hasNext() && nearbyAttractions.size() <= 5) {
			Map.Entry mentry = (Map.Entry)itr.next();
			System.out.println("Valeur: "+mentry.getValue());
			nearbyAttractions.add(allAtractions.get((int) mentry.getValue()));
		}*/


		for (Map.Entry<Double, Integer> attraction : mapdistance.entrySet()) {
			nearbyAttractions.add(allAtractions.get(attraction.getValue()));
			if (nearbyAttractions.size() >= 5) return nearbyAttractions;
		}

		return nearbyAttractions;
	}

	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
			}
		});
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
		logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
