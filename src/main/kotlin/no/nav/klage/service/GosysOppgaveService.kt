package no.nav.klage.service

import no.nav.klage.api.controller.view.GosysOppgaveView
import no.nav.klage.clients.gosysoppgave.*
import no.nav.klage.clients.pdl.PdlClient
import no.nav.klage.kodeverk.Tema
import no.nav.klage.util.TokenUtil
import no.nav.klage.util.getLogger
import no.nav.klage.util.getSecureLogger
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class GosysOppgaveService(
    private val gosysOppgaveClient: GosysOppgaveClient,
    private val pdlClient: PdlClient,
    private val kabalApiService: KabalApiService,
    private val microsoftGraphService: MicrosoftGraphService,
    private val tokenUtil: TokenUtil,
) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
        private val secureLogger = getSecureLogger()
    }

    fun getGosysOppgaveList(fnr: String, tema: Tema?): List<GosysOppgaveView> {
        val aktoerId = pdlClient.hentAktoerIdent(fnr = fnr)

        val gosysOppgaveList = gosysOppgaveClient.fetchGosysOppgaverForAktoerIdAndTema(
            aktoerId = aktoerId,
            tema = tema,
        )
        //TODO: Legg til filter i spørringen mot oppgave-api
        return gosysOppgaveList.map { it.toOppgaveView() }.filter { it.oppgavetype !in listOf("Journalføring", "Kontakt bruker") }
    }

    fun getGjelderKodeverkForTema(tema: Tema): List<Gjelder> {
        return gosysOppgaveClient.getGjelderKodeverkForTema(tema = tema)
    }

    fun getGosysOppgavetypeKodeverkForTema(tema: Tema): List<GosysOppgavetypeResponse> {
        return gosysOppgaveClient.getGosysOppgavetypeKodeverkForTema(tema = tema)
    }

    fun updateGosysOppgave(
        gosysOppgaveId: Long,
        frist: LocalDate,
        tildeltSaksbehandlerIdent: String?
    ) {
        val currentUserIdent = tokenUtil.getCurrentIdent()
        val currentUserInfo = microsoftGraphService.getSaksbehandlerPersonligInfo(navIdent = currentUserIdent)
        val currentGosysOppgave = gosysOppgaveClient.getGosysOppgave(gosysOppgaveId = gosysOppgaveId)

        val newComment = "Overførte oppgaven fra Kabin til Kabal."

        var newBeskrivelsePart = "$newComment\nOppdaterte frist."

        val (tilordnetRessurs, tildeltEnhetsnr) = if (tildeltSaksbehandlerIdent != null) {
            val tildeltSaksbehandlerInfo =
                microsoftGraphService.getSaksbehandlerPersonligInfo(tildeltSaksbehandlerIdent)
            newBeskrivelsePart += "\nTildelte oppgaven til $tildeltSaksbehandlerIdent."
            tildeltSaksbehandlerIdent to tildeltSaksbehandlerInfo.enhet.enhetId
        } else {
            null to null
        }
        gosysOppgaveClient.updateGosysOppgave(
            gosysOppgaveId = gosysOppgaveId,
            updateGosysOppgaveInput = UpdateGosysOppgaveInput(
                versjon = currentGosysOppgave.versjon,
                fristFerdigstillelse = frist,
                mappeId = null,
                endretAvEnhetsnr = currentUserInfo.enhet.enhetId,
                tilordnetRessurs = tilordnetRessurs,
                tildeltEnhetsnr = tildeltEnhetsnr,
                beskrivelse = getNewBeskrivelse(
                    newBeskrivelsePart = newBeskrivelsePart,
                    existingBeskrivelse = currentGosysOppgave.beskrivelse,
                    currentUserInfo = currentUserInfo
                ),
                kommentar = UpdateGosysOppgaveInput.Kommentar(
                    tekst = newComment,
                    automatiskGenerert = true
                ),
                tema = null,
                prioritet = null,
                orgnr = null,
                status = null,
                behandlingstema = null,
                behandlingstype = null,
                aktivDato = null,
                oppgavetype = null,
                journalpostId = null,
                saksreferanse = null,
                behandlesAvApplikasjon = null,
                personident = null,
            )
        )
    }

    private fun getNewBeskrivelse(
        newBeskrivelsePart: String,
        existingBeskrivelse: String?,
        currentUserInfo: MicrosoftGraphService.SaksbehandlerPersonligInfo,
    ): String {
        val formattedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

        val nameOfCurrentUser = currentUserInfo.sammensattNavn
        val currentUserEnhet = currentUserInfo.enhet.enhetId
        val header = "--- $formattedDate $nameOfCurrentUser (${currentUserInfo.navIdent}, $currentUserEnhet) ---"
        return "$header\n$newBeskrivelsePart\n\n$existingBeskrivelse\n".trimIndent()
    }

    fun GosysOppgaveRecord.toOppgaveView(): GosysOppgaveView {
        val tema = Tema.fromNavn(tema)
        val alreadyUsed = kabalApiService.gosysOppgaveIsDuplicate(gosysOppgaveId = id)
        return GosysOppgaveView(
            id = id,
            temaId = tema.id,
            gjelder = getGjelder(behandlingstype = behandlingstype, tema = tema),
            oppgavetype = getGosysOppgavetype(oppgavetype = oppgavetype, tema = tema),
            opprettetAv = opprettetAv,
            tildeltEnhetsnr = tildeltEnhetsnr,
            beskrivelse = beskrivelse,
            endretAv = endretAv,
            endretAvEnhetsnr = endretAvEnhetsnr,
            endretTidspunkt = endretTidspunkt,
            opprettetAvEnhetsnr = opprettetAvEnhetsnr,
            opprettetTidspunkt = opprettetTidspunkt,
            fristFerdigstillelse = fristFerdigstillelse,
            alreadyUsed = alreadyUsed,
        )
    }

    private fun getGjelder(behandlingstype: String?, tema: Tema): String? {
        return getGjelderKodeverkForTema(tema = tema).firstOrNull { it.behandlingstype == behandlingstype }?.behandlingstypeTerm
    }

    private fun getGosysOppgavetype(oppgavetype: String?, tema: Tema): String? {
        return getGosysOppgavetypeKodeverkForTema(tema = tema).firstOrNull { it.oppgavetype == oppgavetype }?.term
    }
}