package ws.palladian.utils;

import ws.palladian.core.Instance;

/**
 * Describes a split in training and testing/validation data.
 * @author pk
 * @deprecated Use {@link ws.palladian.core.dataset.split.TrainTestSplit} instead!
 */
@Deprecated
public interface TrainTestSplit {

	/**
	 * @return The training set.
	 */
	Iterable<? extends Instance> getTrain();

	/**
	 * @return The testing/validation set.
	 */
	Iterable<? extends Instance> getTest();

}
