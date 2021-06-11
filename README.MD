### Java reimplementation of TCP/IP for ad-hoc wireless networks

TCP/IP reimplementation in Java for ad-hoc wireless networks. Includes a simulation of users having to connect to each other wirelessly in an ad-hoc network. This is done via a negotiation protocol, which tries to give every user time to send without accidentally creating interference for other users' broadcasts. 

Run framework/MyProtocol.java's main method to start.
Run this method with an integer as argument to assign this node an IP address. This is useful for testing TCP and such without having to use the buggy access control / routing implementation.

Type "DISCOVERY" (and press return) to enable medium access control and routing on a node (it is buggy, so we leave it disabled by default).
Type "DISCOVERYNOW" to skip the initial 8-12 second waiting phase.
Otherwise, type any message you want to transmit to neighbor nodes.
