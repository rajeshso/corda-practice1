package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.schemas.QueryableState
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.security.PublicKey
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
import net.corda.core.contracts.Requirements.using

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val INVOICE_CONTRACT_ID = "com.template.InvoiceContract"

class InvoiceContract : Contract {

    // A transaction is considered valid if the verify() function of the contract of each of the transaction's input
    // and output states does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        command.value.verify(tx, command.signers)
    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        fun verify(tx: LedgerTransaction, signers: List<PublicKey>)

        class Issue : Commands {
            override fun verify(tx: LedgerTransaction, signers: List<PublicKey>) {
               "No input should be present" using (tx.inputStates.isEmpty())
               "Atleast one input should be present" using (tx.outputStates.isNotEmpty())
                //TODO write some more validation logic
            }
        }

        class BidAction : Commands {
            override fun verify(tx: LedgerTransaction, signers: List<PublicKey>) {
                //TODO write some more validation logic
                //TODO: Check if the invoice exists, if the invoice is still in the bidding state, if the invoice was originally sent to the funder
            }
        }

        class AcceptBidAction : Commands {
            override fun verify(tx: LedgerTransaction, signers: List<PublicKey>) {
                //TODO write some more validation logic
                //TODO: Check if the invoice is still in the bidding state, if the invoice was originally sent to the funder
                // Also check if the bid continues to be in the pending
            }
        }
    }
}

// *********
// * State *
// *********
data class InvoiceState(override val linearId: UniqueIdentifier,
                        val supplier: AbstractParty,
                        val funder: AbstractParty?,
                        val invoiceDesc: String) : LinearState, QueryableState {
    //TODO: Add a field for OPEN_FOR_BIDS as a boolean
    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is InvoiceSchemaV1 -> InvoiceSchemaV1.PersistentInvoiceSchemaV1(
                    linearId = this.linearId.id,
                    funder = this.funder, //TODO: The funder should be a list of Parties
                    supplier = this.supplier,
                    description = this.invoiceDesc
            )
            else -> throw IllegalArgumentException("Only Invoice and Bids are supported at this moment-")
        }
     }

    override fun supportedSchemas(): Iterable<MappedSchema> =  listOf<MappedSchema>(InvoiceSchemaV1)

    override val participants: List<AbstractParty> get() = listOfNotNull(supplier, funder)
}

data class BidState(override val linearId: UniqueIdentifier,
                        val id: Int,
                        val invoiceID: String, // TODO: This should be a list of InvoiceIDs. Also, the invoiceID can also be UUID
                        val supplier: AbstractParty,
                        val funder: AbstractParty,
                        val price: Double,
                        val status: String) : LinearState, QueryableState {
    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is BidSchemaV1 -> BidSchemaV1.PersistentBidSchemaV1(
                    linearId = this.linearId.id,
                    id = this.id,
                    invoiceID = this.invoiceID,
                    price = this.price,
                    supplier = supplier,
                    funder = funder,
                    status = status
            )
            else -> throw IllegalArgumentException("Only Invoice and Bids are supported at this moment.")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> =  listOf<MappedSchema>(BidSchemaV1)

    override val participants: List<AbstractParty> get() = listOfNotNull<AbstractParty>(supplier, funder)

}

object InvoiceSchemaV1 : MappedSchema(
        schemaFamily = InvoiceSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentInvoiceSchemaV1::class.java)
) {
    @Entity
    @Table(name = "invoices")
    class PersistentInvoiceSchemaV1(
            var linearId: UUID,
            val funder: AbstractParty?,
            val supplier: AbstractParty,
            val description: String
            ) : PersistentState()

}

object BidSchemaV1 : MappedSchema(
        schemaFamily = InvoiceSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentBidSchemaV1::class.java)
) {
    @Entity
    @Table(name = "bids")
    class PersistentBidSchemaV1(
            var linearId: UUID,
            val id : Int,
            val invoiceID : String,
            val price : Double,
            val supplier : AbstractParty?,
            val funder : AbstractParty?,
            val status: String
    ) : PersistentState()

}

object InvoiceSchema
