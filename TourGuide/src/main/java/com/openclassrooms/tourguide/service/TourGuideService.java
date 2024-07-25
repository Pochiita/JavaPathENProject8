package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.dto.AttractionDataDTO;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private final ExecutorService executorService = Executors.newFixedThreadPool(1000);


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
		VisitedLocation visitedLocation = (user.getVisitedLocations().size() > 0) ? user.getLastVisitedLocation()
				: trackUserLocation(user).join();
		return visitedLocation;
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

	/**
	 * Tracks the location of a given user asynchronously and updates their visited locations.
	 *
	 * This method retrieves the user's current location using the GPS utility and updates
	 * the user's list of visited locations accordingly. It also calculates rewards for the
	 * user based on their updated visited locations. The operation is performed in a separate
	 * thread using the provided executor service.
	 *
	 * @param user The user whose location is to be tracked. Must not be null.
	 * @return A CompletableFuture that will be completed with the user's visited location
	 *         once the location has been successfully retrieved and the user's visited
	 *         locations have been updated. The future may complete exceptionally if an
	 *         error occurs during the process.
	 * @throws RuntimeException If an error occurs during user location retrieval, updating
	 *         visited locations, or reward calculation. This includes handling of
	 *         {@link ExecutionException} and {@link InterruptedException}.
	 */
	public CompletableFuture<VisitedLocation> trackUserLocation(User user) {
		CompletableFuture<VisitedLocation> visitedLocation = CompletableFuture.supplyAsync(()->gpsUtil.getUserLocation(user.getUserId()),executorService);
		CompletableFuture updateUserVisitedLocation = new CompletableFuture<>();
		updateUserVisitedLocation = visitedLocation.thenAccept(result->{
			try{
				user.addToVisitedLocations(visitedLocation.get());
				rewardsService.calculateRewards(user).get();
			} catch (ExecutionException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
		return visitedLocation;
	}

	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		List<Attraction> attractions = gpsUtil.getAttractions();
		return attractions.stream()
				.sorted(Comparator.comparingDouble(a -> rewardsService.getDistance(a, visitedLocation.location)))
				.limit(5)
				.collect(Collectors.toList());
	}

	/**
	 * Retrieves the five closest attractions to a specified user based on the user's current location.
	 * For each attraction, it calculates the distance from the user's location and also fetches the number
	 * of reward points the user would earn for visiting that attraction.
	 *
	 * @param user the user for whom the closest attractions are to be retrieved. This user object should
	 *             contain valid location data to ascertain the user's current position.
	 * @return a list of {@link AttractionDataDTO} objects representing the five closest attractions to the user.
	 *         Each object includes the attraction's name, its location, the user's location, the distance
	 *         from the user to the attraction, and the reward points that the user would earn.
	 * @throws IllegalArgumentException if the user parameter is null or if the user's location is unavailable.
	 * @see Location
	 * @see AttractionDataDTO
	 * @see gpsUtil
	 * @see RewardsService
	 */
	public List<AttractionDataDTO> getFiveClosestAttractions(User user) {
		Location userLocation = getUserLocation(user).location;

		return gpsUtil.getAttractions().stream()
				.map(attraction -> {
					Location attractionLocation = new Location(attraction.latitude, attraction.longitude);
					double distance = rewardsService.getDistance(attractionLocation, userLocation);
					int rewardPoint = rewardsService.getRewardPoints(attraction, user);
					return new AttractionDataDTO(attraction.attractionName, attractionLocation,
							userLocation, distance, rewardPoint);
				})
				.sorted(Comparator.comparingDouble(AttractionDataDTO::getDistance))
				.limit(5)
				.collect(Collectors.toList());
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
