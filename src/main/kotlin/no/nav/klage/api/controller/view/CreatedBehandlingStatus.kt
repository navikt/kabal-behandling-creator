package no.nav.klage.api.controller.view

import no.nav.klage.clients.kabalapi.TilknyttetDokument
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

data class CreatedAnkebehandlingStatusView(
    val typeId: String,
    val ytelseId: String,
    val utfallId: String,
    val vedtakDate: LocalDateTime,
    val sakenGjelder: PartView,
    val klager: PartView,
    val fullmektig: PartView?,
    val mottattNav: LocalDate,
    val mottattKlageinstans: LocalDate,
    val frist: LocalDate,
    val fagsakId: String,
    val fagsystemId: String,
    val journalpost: DokumentReferanse,
    val tildeltSaksbehandler: TildeltSaksbehandler?,
)

data class CreatedKlagebehandlingStatusView(
    val typeId: String,
    val behandlingId: UUID,
    val ytelseId: String,
    val utfall: String,
    val utfallId: String,
    val vedtakDate: LocalDate,
    val sakenGjelder: PartView,
    val klager: PartView,
    val fullmektig: PartView?,
    val mottattVedtaksinstans: LocalDate,
    val mottattKlageinstans: LocalDate,
    val frist: LocalDate,
    val fagsakId: String,
    val fagsystemId: String,
    val journalpost: DokumentReferanse,
    val tildeltSaksbehandler: TildeltSaksbehandler?,
)

data class TildeltSaksbehandler(
    val navIdent: String,
    val navn: String,
)