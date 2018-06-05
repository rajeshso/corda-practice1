package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.node.StatesToRecord
import javax.ws.rs.*

// *****************
// * API Endpoints *
// *****************
@Path("template")
class TemplateApi(val rpcOps: CordaRPCOps) {
    // Accessible at /api/template/templateGetEndpoint.
    @GET
    @Path("templateGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response
                .status(Response.Status.OK)
                .entity("Template GET endpoint")
                .build()
        return Response.ok("Template GET endpoint.").build()
    }

}

// *********
// * Flows *
// *********
//Supplier first flow
@InitiatingFlow
@StartableByRPC
class UploadInvoiceOnChain(private val invoice: InvoiceMessage) : FlowLogic<SignedTransaction>() {
    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new invoice.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contracts constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object GATHERING_SIGNATURES : ProgressTracker.Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }
        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGNATURES,
                FINALISING_TRANSACTION
        )
    }
    override val progressTracker = tracker()

    @Suspendable
    override fun call() : SignedTransaction {
        val notary = serviceHub
                .networkMapCache
                .notaryIdentities.first()
        val supplier =  serviceHub.myInfo.legalIdentities.first()

        //Step 1 - Create Unsigned Transaction
        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputState = InvoiceState(UniqueIdentifier(), supplier,null, invoice.description )

        val command = Command(
                value=InvoiceContract.Commands.IssueAction(),
                signers= listOf(ourIdentity.owningKey))


        val transactionBuilder = TransactionBuilder(notary)
                .addOutputState(outputState, INVOICE_CONTRACT_ID)
                .addCommand(command)

        //Step 2 - Verify Transaction
        progressTracker.currentStep = VERIFYING_TRANSACTION
        transactionBuilder.verify(serviceHub)

        //Step 3 - Sign the Transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val selfSignedTransaction = serviceHub.signInitialTransaction(transactionBuilder)


        // Stage 4 - Finalize transaction
        progressTracker.currentStep = FINALISING_TRANSACTION
        val ftx = subFlow(FinalityFlow(selfSignedTransaction))

        subFlow(BroadcastInvoice(ftx))
        return ftx
    }

}

@InitiatingFlow
class BroadcastInvoice(val stx: SignedTransaction) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() : Unit {
        val funder = serviceHub
                .networkMapCache
                .getPeerByLegalName(CordaX500Name("Funder1", "London", "GB")) ?: throw FlowException("No Funder by this name")
        val session = initiateFlow(funder)
        subFlow(SendTransactionFlow(session, stx ))
    }
}

//Supplier Receiver second flow
@InitiatedBy(Bid::class)
class AcceptBids(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                //TODO: Write some checks
            }
        }
        return subFlow(signTransactionFlow)
    }
}

//Funder Receiver first flow
@InitiatedBy(BroadcastInvoice::class)
class RecordInvoices(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val flow = ReceiveTransactionFlow(
                otherSideSession = counterpartySession,
                checkSufficientSignatures = true,
                statesToRecord = StatesToRecord.ALL_VISIBLE
        )
        subFlow(flow)
    }
}

//Funder Second Flow
@InitiatingFlow
@StartableByRPC
class Bid(private val bidMessage: BidMessage) : FlowLogic<SignedTransaction>() {


    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new bid.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contracts constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with my private key.")
        object GATHERING_SIGNATURES : ProgressTracker.Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGNATURES,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub
                .networkMapCache
                .notaryIdentities.first()
        val funder =  serviceHub.myInfo.legalIdentities.first()
        val supplier = serviceHub
                .networkMapCache
                .getPeerByLegalName(CordaX500Name("Supplier", "London", "GB")) ?: throw FlowException("No Supplier by this name")

        // Stage 1 - Create unsigned transaction
        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputState = BidState(
                linearId = UniqueIdentifier(),
                id = bidMessage.id,
                invoiceID = bidMessage.invoiceID, //TODO: This should be the UUID of the invoice
                price = bidMessage.price,
                supplier = supplier,
                funder = funder,
                status = "PENDING"
        )
        val command = Command(
                value = InvoiceContract.Commands.BidAction(),
                signers = outputState.participants.map { it.owningKey }
        )

        val transactionBuilder = TransactionBuilder(notary)
                .addOutputState(outputState, INVOICE_CONTRACT_ID)
                .addCommand(command)

        // Stage 2 - Verify transaction
        progressTracker.currentStep = VERIFYING_TRANSACTION
        transactionBuilder.verify(serviceHub)

        // Stage 3 - Sign the transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val selfSignedTx = serviceHub.signInitialTransaction(transactionBuilder)

        // Stage 4 - Gather counterparty signatures
        progressTracker.currentStep = GATHERING_SIGNATURES
        val requiredSignatureFlowSessions = listOf<FlowSession>(initiateFlow(serviceHub.identityService.requireWellKnownPartyFromAnonymous(outputState.supplier)))

        val fullySignedTransaction = subFlow(CollectSignaturesFlow(
                selfSignedTx,
                requiredSignatureFlowSessions,
                GATHERING_SIGNATURES.childProgressTracker()))

        // Stage 5 - Finalize transaction
        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(
                transaction = fullySignedTransaction,
                progressTracker = FINALISING_TRANSACTION.childProgressTracker()))

    }
}

@CordaSerializable
data class InvoiceMessage (val id: Int, val description: String, val potentialFunder: String)

@CordaSerializable
data class BidMessage (val id: Int, val invoiceID: String, val price: Double, val supplier: String)

// ***********
// * Plugins *
// ***********
class TemplateWebPlugin : WebServerPluginRegistry {
    // A list of classes that expose web JAX-RS REST APIs.
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TemplateApi))
    //A list of directories in the resources directory that will be served by Jetty under /web.
    // This template's web frontend is accessible at /web/template.
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the templateWeb directory in resources to /web/template
            "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )
}

// Serialization whitelist.
class TemplateSerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(TemplateData::class.java)
}

// This class is not annotated with @CordaSerializable, so it must be added to the serialization whitelist, above, if
// we want to send it to other nodes within a flow.
data class TemplateData(val payload: String)
