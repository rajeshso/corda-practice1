package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.*
import java.util.function.Function
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import net.corda.core.contracts.Command
import net.corda.core.transactions.TransactionBuilder
import com.template.INVOICE_CONTRACT_ID
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.utilities.getOrThrow
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

    @POST
    @Path("issue")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun issueInvoice(message:  InvoiceMessage): Response {
        try {
            val flowHandle = rpcOps.startTrackedFlow(::Initiator, message)
            flowHandle.progress.subscribe { println(">> $it") }
            val result = flowHandle.returnValue.getOrThrow()
            return Response
                    .status(Response.Status.CREATED)
                    .entity("Invoice created")
                    .build()
        } catch (ex: Throwable) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(ex.message)
                    .build()
        }
    }
}

// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class Initiator(private val invoice: InvoiceMessage) : FlowLogic<SignedTransaction>() {
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
                signers= listOf(supplier.owningKey))


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
        return subFlow(FinalityFlow(
                transaction = selfSignedTransaction,
                progressTracker = FINALISING_TRANSACTION.childProgressTracker()))
    }

}

@CordaSerializable
data class InvoiceMessage (val description: String)

@InitiatedBy(Initiator::class)
class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        return Unit
    }
}

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
