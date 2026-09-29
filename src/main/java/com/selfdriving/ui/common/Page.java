package com.selfdriving.ui.common;

import javafx.scene.Node;

/** One screen reachable from the navigation rail. */
public interface Page {

    Node node();

    /** The page has become visible (reload data here). */
    default void shown() {
    }

    /** Another page is now showing. */
    default void hidden() {
    }

    /** The user logged out; release anything held. */
    default void dispose() {
    }
}
