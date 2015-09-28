package tillerino.tillerinobot;

import static org.apache.commons.lang3.StringUtils.getLevenshteinDistance;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import org.tillerino.osuApiModel.Mods;
import org.tillerino.osuApiModel.OsuApiBeatmap;
import org.tillerino.osuApiModel.OsuApiUser;
import org.tillerino.osuApiModel.types.BeatmapId;
import org.tillerino.osuApiModel.types.BitwiseMods;
import org.tillerino.osuApiModel.types.UserId;

import tillerino.tillerinobot.RecommendationsManager.Sampler.Settings;
import tillerino.tillerinobot.lang.Language;
import tillerino.tillerinobot.mbeans.AbstractMBeanRegistration;
import tillerino.tillerinobot.mbeans.CacheMXBean;
import tillerino.tillerinobot.mbeans.CacheMXBeanImpl;
import tillerino.tillerinobot.mbeans.RecommendationsManagerMXBean;
import tillerino.tillerinobot.predicates.PredicateParser;
import tillerino.tillerinobot.predicates.RecommendationPredicate;
import tillerino.tillerinobot.UserException.RareUserException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Communicates with the backend and creates recommendations samplers as well as caching information.
 * 
 * @author Tillerino
 */
@Singleton
public class RecommendationsManager extends AbstractMBeanRegistration implements RecommendationsManagerMXBean {
	/**
	 * Recommendation as returned by the backend. Needs to be enriched before being displayed.
	 * 
	 * @author Tillerino
	 */
	public interface BareRecommendation {
		@BeatmapId
		int getBeatmapId();
		
		/**
		 * mods for this recommendation
		 * @return 0 for no mods, -1 for unknown mods, any other long for mods according to {@link Mods}
		 */
		@BitwiseMods
		long getMods();
		
		long[] getCauses();
		
		/**
		 * returns a guess at how much pp the player could achieve for this recommendation
		 * @return null if no personal pp were calculated
		 */
		Integer getPersonalPP();
		
		/**
		 * @return this is not normed, so the sum of all probabilities can be greater than 1 and this must be accounted for!
		 */
		double getProbability();
	}
	
	@Data
	public static class GivenRecommendation {
		public GivenRecommendation(@UserId int userid, @BeatmapId int beatmapid, long date, @BitwiseMods long mods) {
			super();
			this.userid = userid;
			this.beatmapid = beatmapid;
			this.date = date;
			this.mods = mods;
		}

		protected GivenRecommendation() {

		}

		@UserId
		@Getter(onMethod = @__(@UserId))
		@Setter(onParam = @__(@UserId))
		public int userid;
		@BeatmapId
		@Getter(onMethod = @__(@BeatmapId))
		@Setter(onParam = @__(@BeatmapId))
		public int beatmapid;
		public long date;
		@BitwiseMods
		@Getter(onMethod = @__(@BitwiseMods))
		@Setter(onParam = @__(@BitwiseMods))
		public long mods;
	}
	
	/**
	 * Enriched Recommendation.
	 * 
	 * @author Tillerino
	 */
	public static class Recommendation {
		public BeatmapMeta beatmap;
		
		public BareRecommendation bareRecommendation;
	}
	
	/**
	 * The type of recommendation model that the player has chosen.
	 * 
	 * @author Tillerino
	 */
	public enum Model {
		ALPHA,
		BETA,
		GAMMA
	}
	
	/**
	 * Thrown when a loaded beatmap has neither aproved status 1 or 2.
	 * 
	 *	// TODO find out about "qualified" = 3
	 * 
	 * @author Tillerino
	 */
	public static class NotRankedException extends UserException {
		private static final long serialVersionUID = 1L;

		public NotRankedException(String message) {
			super(message);
		}
	}
	
	/**
	 * Distribution for recommendations. Changes upon sampling.
	 * 
	 * @author Tillerino
	 */
	public static class Sampler {
		@EqualsAndHashCode
		public static class Settings {
			public boolean nomod;
			public Model model;
			@BitwiseMods
			public long requestedMods;
			public List<RecommendationPredicate> predicates = new ArrayList<>();
		}
		final SortedMap<Double, BareRecommendation> distribution = new TreeMap<>();
		double sum = 0;
		final Random random = new Random();
		final Settings settings;
		public Sampler(Collection<BareRecommendation> recommendations, Settings settings) {
			for (BareRecommendation bareRecommendation : recommendations) {
				sum += bareRecommendation.getProbability();
				distribution.put(sum, bareRecommendation);
			}
			this.settings = settings;
		}
		public boolean isEmpty() {
			return distribution.isEmpty();
		}
		public BareRecommendation sample() {
			double x = random.nextDouble() * sum;
			
			SortedMap<Double, BareRecommendation> rest = distribution.tailMap(x);
			if(rest.size() == 0) {
				// this means that there was some extreme numerical instability.
				// this is practically not possible. at least not *maximum stack
				// size* times in a row.
				return sample();
			}
			
			sum = rest.firstKey();
			
			BareRecommendation sample = rest.remove(sum);
			
			sum -= sample.getProbability();
			
			Collection<BareRecommendation> refill = new ArrayList<>();
			
			while(!rest.isEmpty()) {
				refill.add(rest.remove(rest.firstKey()));
			}
			
			for(BareRecommendation bareRecommendation : refill) {
				sum += bareRecommendation.getProbability();
				distribution.put(sum, bareRecommendation);
			}
			
			return sample;
		}
	}
	
	BotBackend backend;

	PredicateParser parser = new PredicateParser();

	@Inject
	public RecommendationsManager(BotBackend backend) {
		this.backend = backend;
	}

	@Override
	public ObjectName preRegister(MBeanServer server, ObjectName objectName)
			throws Exception {
		server.registerMBean(givenRecomendationsMXBean, null);
		server.registerMBean(samplersMXBean, null);

		return super.preRegister(server, objectName);
	}

	public Cache<Integer, Recommendation> lastRecommendation = CacheBuilder
			.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();

	public LoadingCache<Integer, List<Integer>> givenRecomendations =  CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.HOURS).recordStats().build(new CacheLoader<Integer, List<Integer>>() {
		@Override
		public List<Integer> load(@UserId Integer key) throws Exception {
			List<Integer> list = new ArrayList<>();
			for (GivenRecommendation recommendation : backend.loadGivenRecommendations(key)) {
				list.add(recommendation.getBeatmapid());
			}
			return list;
		}
	});
	
	public CacheMXBean givenRecomendationsMXBean = new CacheMXBeanImpl(givenRecomendations, getClass(), "givenRecommendations");
	
	@Override
	public CacheMXBean fetchGivenRecommendations() {
		return givenRecomendationsMXBean;
	}

	/**
	 * These take long to calculate, so we want to keep them for a bit, but they also take a lot of space. 100 should be a good balance.
	 */
	public Cache<Integer, Sampler> samplers = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(100).build();

	public CacheMXBean samplersMXBean = new CacheMXBeanImpl(samplers, getClass(), "samplers");

	@Override
	public CacheMXBean fetchSamplers() {
		return samplersMXBean;
	}

	@CheckForNull
	public Recommendation getLastRecommendation(Integer userid) {
		return lastRecommendation.getIfPresent(userid);
	}

	/**
	 * get an ready-to-display recommendation
	 * 
	 * @param apiUser
	 * @param message
	 *            the remaining arguments ("r" or "recommend" were removed).
	 *            null if an existing sampler should be reused.
	 * @param lang
	 * @return
	 * @throws UserException
	 * @throws SQLException
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	public Recommendation getRecommendation(@Nonnull OsuApiUser apiUser, @CheckForNull String message, Language lang)
			throws UserException, SQLException, IOException, InterruptedException {
		/*
		 * log activity making sure that we can resolve the user's IRC name
		 */
		
		int userid = apiUser.getUserId();
		
		backend.registerActivity(userid);
		
		/*
		 * load sampler
		 */

		Sampler sampler = samplers.getIfPresent(userid);

		if (sampler == null || message != null) {
			/*
			 * parse arguments
			 */

			Settings settings = parseSamplerSettings(apiUser, message == null ? "" : message, lang);

			if (sampler == null || !sampler.settings.equals(settings)) {
				Collection<BareRecommendation> recommendations = backend.loadRecommendations(userid,
						givenRecomendations.getUnchecked(userid), settings.model, settings.nomod,
						settings.requestedMods);

				// only keep the 1k most probable recommendations to save some
				// memory
				recommendations = getTopRecommendations(recommendations, settings.predicates);

				sampler = new Sampler(recommendations, settings);

				samplers.put(userid, sampler);
			}
		}

		if (sampler.isEmpty()) {
			samplers.invalidate(userid);
			throw new UserException(lang.outOfRecommendations());
		}

		/*
		 * sample and load meta data
		 */

		BareRecommendation sample = sampler.sample();

		int beatmapid = sample.getBeatmapId();

		Recommendation recommendation = new Recommendation();

		recommendation.bareRecommendation = sample;

		BeatmapMeta loadBeatmap;
		try {
			if (sample.getMods() < 0) {
				loadBeatmap = backend.loadBeatmap(beatmapid, 0, lang);
			} else {
				loadBeatmap = backend.loadBeatmap(beatmapid, sample.getMods(),
						lang);
				if (loadBeatmap == null)
					loadBeatmap = backend.loadBeatmap(beatmapid, 0, lang);
			}
		} catch (NotRankedException e) {
			throw new RareUserException(lang.excuseForError());
		}
		if (loadBeatmap == null) {
			throw new RareUserException(lang.excuseForError());
		}
		recommendation.beatmap = loadBeatmap;

		loadBeatmap.setPersonalPP(sample.getPersonalPP());

		/*
		 * save recommendation internally
		 */

		givenRecomendations.getUnchecked(userid).add(beatmapid);
		lastRecommendation.put(userid, recommendation);

		return recommendation;
	}

	public Settings parseSamplerSettings(OsuApiUser apiUser, @Nonnull String message,
			Language lang) throws UserException, SQLException, IOException {
		String[] remaining = message.split(" ");
		
		Settings settings = new Settings();
		
		settings.model = Model.GAMMA;
		
		

		for (int i = 0; i < remaining.length; i++) {
			if (userData.nomodoption)
		settings.nomod = true;
		else
		settings.nomod = false;
			String param = remaining[i];
			String lowerCase = param.toLowerCase();
			if(lowerCase.length() == 0)
				continue;
			if(getLevenshteinDistance(lowerCase, "nomod") <= 2) {
				settings.nomod = true;
				continue;
			}
			if(getLevenshteinDistance(lowerCase, "relax") <= 2) {
				settings.model = Model.ALPHA;
				continue;
			}
			if(getLevenshteinDistance(lowerCase, "beta") <= 1) {
				settings.model = Model.BETA;
				continue;
			}
			if(getLevenshteinDistance(lowerCase, "gamma") <= 2) {
				settings.model = Model.GAMMA;
				continue;
			}
			if(settings.model == Model.GAMMA && (lowerCase.equals("dt") || lowerCase.equals("nc"))) {
				settings.requestedMods = Mods.add(settings.requestedMods, Mods.DoubleTime);
				continue;
			}
			if(settings.model == Model.GAMMA &&  lowerCase.equals("hr")) {
				settings.requestedMods = Mods.add(settings.requestedMods, Mods.HardRock);
				continue;
			}
			if(settings.model == Model.GAMMA &&  lowerCase.equals("hd")) {
				settings.requestedMods = Mods.add(settings.requestedMods, Mods.Hidden);
				continue;
			}
			if (backend.getDonator(apiUser) > 0) {
				RecommendationPredicate predicate = parser.tryParse(param, lang);
				if (predicate != null) {
					for (RecommendationPredicate existingPredicate : settings.predicates) {
						if (existingPredicate.contradicts(predicate)) {
							throw new UserException(lang.invalidChoice(
									existingPredicate.getOriginalArgument() + " with "
											+ predicate.getOriginalArgument(),
									"either " + existingPredicate.getOriginalArgument() + " or "
											+ predicate.getOriginalArgument()));
						}
					}
					settings.predicates.add(predicate);
					continue;
				}
			}
			throw new UserException(lang.invalidChoice(param,
					"[nomod] [relax|beta|gamma] [dt] [hr] [hd]"));
		}
		
		/*
		 * verify the arguments
		 */
		
		if(settings.nomod && settings.requestedMods != 0) {
			throw new UserException(lang.mixedNomodAndMods());
		}
		
		return settings;
	}

	/**
	 * returns
	 * 
	 * @param recommendations
	 * @param predicates
	 * @return
	 * @throws IOException
	 * @throws SQLException
	 */
	public List<BareRecommendation> getTopRecommendations(
			Collection<BareRecommendation> recommendations,
			List<RecommendationPredicate> predicates) throws SQLException, IOException {
		List<BareRecommendation> list = new ArrayList<>();

		recommendationsLoop: for (BareRecommendation bareRecommendation : recommendations) {
			OsuApiBeatmap beatmap = null;
			for (RecommendationPredicate predicate : predicates) {
				if (beatmap == null) {
					beatmap = backend.getBeatmap(bareRecommendation.getBeatmapId());
				}
				if (!predicate.test(bareRecommendation, beatmap)) {
					continue recommendationsLoop;
				}
			}
			list.add(bareRecommendation);
		}
		
		Collections.sort(list, new Comparator<BareRecommendation>() {
			@Override
			public int compare(BareRecommendation o1, BareRecommendation o2) {
				return (int) Math.signum(o2.getProbability() - o1.getProbability());
			}
		});

		int size = Math.min(list.size(), 1000);
		ArrayList<BareRecommendation> arrayList = new ArrayList<>(size);
		arrayList.addAll(list.subList(0, size));
		
		return arrayList;
	}

	public void forgetRecommendations(@UserId int userid) throws SQLException {
		givenRecomendations.getUnchecked(userid).clear();
		backend.forgetRecommendations(userid);
	}
}
