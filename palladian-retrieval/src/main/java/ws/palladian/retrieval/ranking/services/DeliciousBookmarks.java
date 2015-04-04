package ws.palladian.retrieval.ranking.services;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ws.palladian.retrieval.HttpException;
import ws.palladian.retrieval.HttpResult;
import ws.palladian.retrieval.parser.json.JsonArray;
import ws.palladian.retrieval.parser.json.JsonException;
import ws.palladian.retrieval.ranking.Ranking;
import ws.palladian.retrieval.ranking.RankingService;
import ws.palladian.retrieval.ranking.RankingServiceException;
import ws.palladian.retrieval.ranking.RankingType;

/**
 * <p>
 * RankingService implementation to get the number of bookmarks of a given url on Delicious.
 * </p>
 * <p>
 * Wait at least 1 second between requests. Feeds only updated 1-2 times per hour.
 * </p>
 * TODO use proxies to overcome limits
 * 
 * @author Julien Schmehl
 * @author Philipp Katz
 * @see http://delicious.com/
 * @see http://delicious.com/help/feeds
 */
public final class DeliciousBookmarks extends AbstractRankingService implements RankingService {

    /** The class logger. */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeliciousBookmarks.class);

    /** The id of this service. */
    private static final String SERVICE_ID = "delicious";

    /** The ranking value types of this service **/
    public static final RankingType BOOKMARKS = new RankingType("delicious_bookmarks", "Delicious Bookmarks",
            "The number of bookmarks users have created for this url.");

    /** All available ranking types by {@link DeliciousBookmarks}. */
    private static final List<RankingType> RANKING_TYPES = Arrays.asList(BOOKMARKS);

    @Override
    public Ranking getRanking(String url) throws RankingServiceException {
        Ranking.Builder builder = new Ranking.Builder(this, url);

        Integer result = null;

        try {

            String md5Url = DigestUtils.md5Hex(url);
            HttpResult httpResult = retriever.httpGet("http://feeds.delicious.com/v2/json/urlinfo/" + md5Url);
            String jsonString = httpResult.getStringContent();
            LOGGER.trace("JSON=" + jsonString);
            JsonArray json = new JsonArray(jsonString);

            result = 0;
            if (json.size() > 0) {
                result = json.getJsonObject(0).getInt("total_posts");
            }
            LOGGER.trace("Delicious bookmarks for " + url + " : " + result);

        } catch (JsonException e) {
            throw new RankingServiceException(e);
        } catch (HttpException e) {
            throw new RankingServiceException(e);
        }

        builder.add(BOOKMARKS, result);
        return builder.create();
    }

    @Override
    public String getServiceId() {
        return SERVICE_ID;
    }

    @Override
    public List<RankingType> getRankingTypes() {
        return RANKING_TYPES;
    }
}
