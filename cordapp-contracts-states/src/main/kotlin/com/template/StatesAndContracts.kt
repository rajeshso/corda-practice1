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

        class IssueAction : Commands {
            override fun verify(tx: LedgerTransaction, signers: List<PublicKey>) {
               "No input should be present" using (tx.inputStates.isEmpty())
               "Atleast one input should be present" using (tx.outputStates.isNotEmpty())
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
    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is InvoiceSchemaV1 -> InvoiceSchemaV1.PersistentInvoiceSchemaV1(
                    linearId = this.linearId.id,
                    funder = this.funder,
                    supplier = this.supplier,
                    description = this.invoiceDesc
            )
            else -> throw IllegalArgumentException("Only Invoice is supported at this moment")
        }
     }

    override fun supportedSchemas(): Iterable<MappedSchema> =  listOf<MappedSchema>(InvoiceSchemaV1)

    override val participants: List<AbstractParty> get() = listOfNotNull(supplier, funder)
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

object InvoiceSchema {

}
