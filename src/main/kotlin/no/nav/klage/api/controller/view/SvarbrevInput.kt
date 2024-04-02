package no.nav.klage.api.controller.view

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDate

@JsonIgnoreProperties(ignoreUnknown = true)
data class PreviewAnkeSvarbrevInput(
    val mottattKlageinstans: LocalDate,
    val fristInWeeks: Int,
    val sakenGjelder: PartId,
    val ytelseId: String,
    val svarbrevInput: SvarbrevInput,
)

data class SvarbrevInput(
    val title: String,
    val receivers: List<Receiver>,
    val enhetId: String,
    val fullmektigFritekst: String?,
) {
    data class Receiver(
        val id: String,
        val handling: HandlingEnum,
        val overriddenAddress: AddressInput?,
    ) {
        data class AddressInput(
            val adresselinje1: String?,
            val adresselinje2: String?,
            val adresselinje3: String?,
            val landkode: String,
            val postnummer: String?,
        )

        enum class HandlingEnum {
            AUTO,
            LOCAL_PRINT,
            CENTRAL_PRINT
        }
    }
}