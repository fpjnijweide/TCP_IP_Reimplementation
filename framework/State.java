package framework;

public enum State {
    READY, // This is a state which does not use access control, for testing the TCP implementation.

    // Other states are described in the report.
    DISCOVERY,
    SENT_DISCOVERY,
    NEGOTIATION_MASTER,
    TIMING_SLAVE,
    TIMING_MASTER,
    TIMING_STRANGER,
    NEGOTIATION_STRANGER,
    POST_NEGOTIATION_MASTER,
    WAITING_FOR_TIMING_STRANGER,
    NEGOTIATION_STRANGER_DONE,
    POST_NEGOTIATION_SLAVE,
    POST_NEGOTIATION_STRANGER,
    REQUEST_SLAVE,
    REQUEST_MASTER,
    POST_REQUEST_MASTER,
    POST_REQUEST_SLAVE,
    DATA_PHASE
}
