package ws.palladian.retrieval.semantics;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * This class represents a single word which is held in the {@link WordDB}.
 * 
 * @author David Urbansky
 * 
 */
public class Word {

    /** The database id. */
    private int id = -1;

    /** The actual word. */
    private String word = "";

    /** The type of the word, e.g. "noun" or "adjective". */
    private String type = "";

    /** The language of the word. */
    private String language = "";

    /** A set of synonyms for this word. */
    private Set<Word> synonyms = new LinkedHashSet<Word>();

    /** A set of hypernyms for this word. */
    private Set<Word> hypernyms = new LinkedHashSet<Word>();

    public Word(int id, String word, String type, String language) {
        super();
        this.id = id;
        this.word = word;
        this.type = type;
        this.language = language;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Set<Word> getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(Set<Word> synonyms) {
        this.synonyms = synonyms;
    }

    public Set<Word> getHypernyms() {
        return hypernyms;
    }

    public void setHypernyms(Set<Word> hypernyms) {
        this.hypernyms = hypernyms;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + id;
        result = prime * result + (word == null ? 0 : word.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Word other = (Word) obj;
        if (id != other.id) {
            return false;
        }
        if (word == null) {
            if (other.word != null) {
                return false;
            }
        } else if (!word.equals(other.word)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Word [id=");
        builder.append(id);
        builder.append(", word=");
        builder.append(word);
        builder.append(", type=");
        builder.append(type);
        builder.append(", language=");
        builder.append(language);
        builder.append(", synonyms=");

        int i = 0;
        for (Word synonym : synonyms) {
            if (i++ >= 1) {
                builder.append(",");
            }
            builder.append(synonym.getWord());
        }

        builder.append(", hypernyms=");

        i = 0;
        for (Word hypernym : hypernyms) {
            if (i++ >= 1) {
                builder.append(",");
            }
            builder.append(hypernym.getWord());
        }

        builder.append("]");
        return builder.toString();
    }
}