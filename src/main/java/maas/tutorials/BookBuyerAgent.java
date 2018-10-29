package maas.tutorials;

import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.basic.Action;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.FIPANames;
import jade.domain.JADEAgentManagement.JADEManagementOntology;
import jade.domain.JADEAgentManagement.ShutdownPlatform;
import jade.lang.acl.ACLMessage;


@SuppressWarnings("serial")
public class BookBuyerAgent extends Agent {
	
	private String targetBookTitle;
	private AID[] sellerAgents;
	
	protected void setup() {
	// Printout a welcome message
		System.out.println("Hello! Buyer-agent "+getAID().getName()+" is ready.");

		// Get the title of the book to buy as a start-up argument 
		Object[] args = getArguments(); 
		if (args != null && args.length > 0) {
			targetBookTitle = (String) args[0]; 
			System.out.println(“Trying to buy “+targetBookTitle);
			// Add a TickerBehaviour that schedules a request to seller agents every minute 
			addBehaviour(new TickerBehaviour(this, 60000) {
				protected void onTick() {
					// Update the list of seller agents 
					DFAgentDescription template = new DFAgentDescription(); 
					ServiceDescription sd = new ServiceDescription(); 
					sd.setType(“book-selling”); 
					template.addServices(sd); 
					try {
						DFAgentDescription[] result = DFService.search(myAgent, template); 
						sellerAgents = new AID[result.length]; 
						for (int i = 0; i < result.length; ++i) { 
							sellerAgents[i] = result[i].getName();
						}
					}
					catch (FIPAException fe) { 
						fe.printStackTrace();
					}
					// Perform the request 
					myAgent.addBehaviour(new RequestPerformer());
				}
			});
		} else {
		// Make the agent terminate immediately 
			System.out.println(“No book title specified“);
			doDelete();
		} 
	}
		// Put agent clean-up operations here
	protected void takeDown() { // Printout a dismissal message 
		System.out.println(“Buyer-agent “+getAID().getName()+” terminating.”);
	}
	

    // Taken from http://www.rickyvanrijn.nl/2017/08/29/how-to-shutdown-jade-agent-platform-programmatically/
	private class shutdown extends OneShotBehaviour{
		public void action() {
			ACLMessage shutdownMessage = new ACLMessage(ACLMessage.REQUEST);
			Codec codec = new SLCodec();
			myAgent.getContentManager().registerLanguage(codec);
			myAgent.getContentManager().registerOntology(JADEManagementOntology.getInstance());
			shutdownMessage.addReceiver(myAgent.getAMS());
			shutdownMessage.setLanguage(FIPANames.ContentLanguage.FIPA_SL);
			shutdownMessage.setOntology(JADEManagementOntology.getInstance().getName());
			try {
			    myAgent.getContentManager().fillContent(shutdownMessage,new Action(myAgent.getAID(), new ShutdownPlatform()));
			    myAgent.send(shutdownMessage);
			}
			catch (Exception e) {
			    //LOGGER.error(e);
			}

		}
	}
}

/** Inner Class for Behaviour */

private class RequestPerformer extends Behaviour { 
	private AID bestSeller; // The agent who provides the best offer 
	private int bestPrice; // The best offered price 
	private int repliesCnt = 0; // The counter of replies from seller agents 
	private MessageTemplate mt; // The template to receive replies
	private int step = 0;
	public void action() { 
		switch (step) {
			case 0: // Send the cfp to all sellers ACLMessage
				cfp = new ACLMessage(ACLMessage.CFP);
				for (int i = 0; i < sellerAgents.length; ++i) {
					cfp.addReceiver(sellerAgents[i]);
				}
				cfp.setContent(targetBookTitle);
				cfp.setConversationId(“book-trade”); 
				cfp.setReplyWith(“cfp”+System.currentTimeMillis()); // Unique value 
				myAgent.send(cfp);
				// Prepare the template to get proposals
				mt = MessageTemplate.and(MessageTemplate.MatchConversationId(“book-trade”), 
									 MessageTemplate.MatchInReplyTo(cfp.getReplyWith()));
				step = 1;
				break;
			case 1: // Receive all proposals/refusals from seller agents 
				ACLMessage reply = myAgent.receive(mt);
				if (reply != null) { // Reply received 
					if (reply.getPerformative() == ACLMessage.PROPOSE) { // This is an offer
						int price = Integer.parseInt(reply.getContent());
						if (bestSeller == null || price < bestPrice) { // This is the best offer at present 
							bestPrice = price; 
							bestSeller = reply.getSender();
						}
					}
					repliesCnt++;
					if (repliesCnt >= sellerAgents.length) { // We received all replies 
						step = 2;
					}
				} else {
					block();
				} 
				break; 
			case 2:
				// Send the purchase order to the seller that provided the best offer 
				ACLMessage order = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL); 
				order.addReceiver(bestSeller); 
				order.setContent(targetBookTitle); 
				order.setConversationId(“book-trade”); 
				order.setReplyWith(“order”+System.currentTimeMillis()); 
				myAgent.send(order);
				// Prepare the template to get the purchase order 
				reply mt = MessageTemplate.and(MessageTemplate.MatchConversationId(“book-trade”),
										   MessageTemplate.MatchInReplyTo(order.getReplyWith()));
				step = 3;
				break;
			case 3:
				// Receive the purchase order reply 
				reply = myAgent.receive(mt); 
				if (reply != null) { // Purchase order reply received 
					if (reply.getPerformative() == ACLMessage.INFORM) { // Purchase successful. We can terminate 
						System.out.println(targetBookTitle+“ successfully purchased.”); 
						System.out.println(“Price = ”+bestPrice); 
						myAgent.doDelete();
					} 
					step = 4; 
				} else { 
					block(); 
				} 
				break; 
		}
	}
	public boolean done() {
		return ((step == 2 && bestSeller == null) || step == 4);
	}
} // End of inner class RequestPerformer