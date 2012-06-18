package ws.palladian.model.features;

import ws.palladian.extraction.PipelineDocument;

/**
 * <p>
 * Abstract super class defining an Annotation.
 * </p>
 * 
 * @author Philipp Katz
 * @author Klemens Muthmann
 */
public abstract class Annotation<T> implements Comparable<Annotation<T>> {

    /**
     * <p>
     * The document this {@link Annotation} points to.
     * </p>
     */
    private final PipelineDocument<T> document;

    /**
     * <p>
     * The feature vector of this {@link Annotation}.
     * </p>
     */
    private final FeatureVector featureVector;

    /**
     * <p>
     * Creates a new completely initialized {@link Annotation}.
     * </p>
     * 
     * @param document The {@link PipelineDocument} the {@link Annotation} points to.
     */
    public Annotation(PipelineDocument<T> document) {
        super();
        this.document = document;
        this.featureVector = new FeatureVector();
    }

    /**
     * <p>
     * Provides the {@link PipelineDocument} this {@link Annotation} points to.
     * </p>
     * 
     * @return The {@link PipelineDocument} containing the annotated content.
     */
    public final PipelineDocument<T> getDocument() {
        return this.document;
    }

    /**
     * <p>
     * Provides the position of the first character of this {@link Annotation}.
     * </p>
     * 
     * @return the position of the first character of this {@link Annotation}.
     */
    public abstract Integer getStartPosition();

    /**
     * <p>
     * Provides the position of the first character after the end of this {@link Annotation}.
     * </p>
     * 
     * @return the position of the first character after the end of this {@link Annotation}.
     */
    public abstract Integer getEndPosition();

    /**
     * <p>
     * Provides a running index of this {@link Annotation}. This makes it possible to determine, if {@link Annotation}s
     * have been removed later.
     * </p>
     * 
     * @return the running index of this {@link Annotation}.
     */
    public abstract Integer getIndex();

    /**
     * <p>
     * Provides the value of this {@link Annotation}, usually from the underlying {@link PipelineDocument}.
     * </p>
     * 
     * @return The value of this {@link Annotation} as a {@link String}.
     */
    public abstract String getValue();

    /**
     * <p>
     * Set the value of this {@link Annotation}. Usually, the value depends on supplied positions and is determined
     * directly from the associated {@link PipelineDocument}. This method provides the possibility to manually override
     * the value, which is necessary e.g. when stemming or lemmatization is applied.
     * </p>
     * 
     * @param value
     */
    public abstract void setValue(String value);

    /**
     * <p>
     * The {@link FeatureVector} of this {@link Annotation}.
     * </p>
     * 
     * @return A {@link FeatureVector} containing this {@link Annotation}.
     */
    public final FeatureVector getFeatureVector() {
        return this.featureVector;
    }

    /**
     * <p>
     * The natural ordering of {@code Annotation}s depends on the {@code Annotation}'s start position. An
     * {@code Annotation} with a smaller start position should occur before one with a larger start position in the
     * {@code Annotation}s' natural ordering.
     * </p>
     * 
     * @see #getStartPosition()
     * @param annotation The {@code Annotation} to compare this {@code Annotation} to.
     */
    @Override
    public int compareTo(Annotation<T> annotation) {
        return this.getStartPosition().compareTo(annotation.getStartPosition());
    }

    /**
     * <p>
     * Provides this {@code Annotation}s {@link Feature} identified by the provided {@link FeatureDescriptor}.
     * </p>
     * 
     * @param descriptor The {@code FeatureDescriptor} identifying the desired {@code Feature}
     * @return
     */
    public <F extends Feature<?>> F getFeature(FeatureDescriptor<F> descriptor) {
        return getFeatureVector().get(descriptor);
    }

    //
    // force subclasses to implement the following methods
    //

    @Override
    public abstract String toString();

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();
}