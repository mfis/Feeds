package de.fimatas.feeds.util;

import com.rometools.rome.feed.WireFeed;
import org.apache.commons.lang3.StringUtils;
import org.jdom2.Element;
import org.jdom2.Namespace;

import java.util.Optional;

public class FeedsUtil {

    public static String getForeignMarkupValue(WireFeed wireFeed, String name) {
        return getForeignMarkupElement(wireFeed, name).map(e -> StringUtils.trimToNull(e.getValue())).orElse(null);
    }

    public static Element createElement(String name, String value, Namespace namespace) {
        Element element = new Element(name, namespace);
        element.setText(value);
        return element;
    }

    private static Optional<Element> getForeignMarkupElement(WireFeed wireFeed, String name) {
        return wireFeed.getForeignMarkup().stream().filter(fm -> fm.getName().equals(name)).findFirst();
    }
}
