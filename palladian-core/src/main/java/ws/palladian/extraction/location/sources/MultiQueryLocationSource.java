package ws.palladian.extraction.location.sources;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import ws.palladian.extraction.location.Location;
import ws.palladian.extraction.location.LocationSource;
import ws.palladian.helper.collection.CollectionHelper;
import ws.palladian.helper.constants.Language;

/**
 * <p>
 * Common base class for {@link LocationSource}s which support getting multiple entities in one go (like
 * {@link LocationSource#getLocations(List)}).
 * </p>
 * 
 * @author Philipp Katz
 */
abstract class MultiQueryLocationSource implements LocationSource {

    @Override
    public final Collection<Location> getLocations(String locationName, Set<Language> languages) {
        return getLocations(Collections.singletonList(locationName), languages).get(locationName);
    }

    @Override
    public final Location getLocation(int locationId) {
        return CollectionHelper.getFirst(getLocations(Collections.singletonList(locationId)));
    }

}