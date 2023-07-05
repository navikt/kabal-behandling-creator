package no.nav.klage.service

import no.nav.klage.api.controller.view.CreateAnkeInputView
import no.nav.klage.api.controller.view.PartId
import no.nav.klage.api.controller.view.PartView
import no.nav.klage.api.controller.view.SearchPartInput
import no.nav.klage.clients.KabalInnstillingerClient
import no.nav.klage.clients.KlageFssProxyClient
import no.nav.klage.clients.dokarkiv.*
import no.nav.klage.clients.kabalapi.CompletedKlagebehandling
import no.nav.klage.clients.saf.graphql.Journalpost
import no.nav.klage.clients.saf.graphql.Journalposttype
import no.nav.klage.clients.saf.graphql.Journalstatus
import no.nav.klage.clients.saf.graphql.SafGraphQlClient
import no.nav.klage.exceptions.InvalidProperty
import no.nav.klage.exceptions.JournalpostNotFoundException
import no.nav.klage.exceptions.SectionedValidationErrorWithDetailsException
import no.nav.klage.exceptions.ValidationSection
import no.nav.klage.kodeverk.Tema
import no.nav.klage.kodeverk.Type
import no.nav.klage.kodeverk.Ytelse
import no.nav.klage.util.TokenUtil
import no.nav.klage.util.getLogger
import no.nav.klage.util.getSecureLogger
import org.springframework.stereotype.Service
import java.util.*

@Service
class DokArkivService(
    private val dokArkivClient: DokArkivClient,
    private val genericApiService: GenericApiService,
    private val safGraphQlClient: SafGraphQlClient,
    private val tokenUtil: TokenUtil,
    private val fssProxyClient: KlageFssProxyClient,
    private val kabalInnstillingerClient: KabalInnstillingerClient
) {

    companion object {
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val logger = getLogger(javaClass.enclosingClass)
        private val secureLogger = getSecureLogger()
    }

    private fun getBruker(sakenGjelder: no.nav.klage.clients.kabalapi.PartView): Bruker {
        return Bruker(
            id = sakenGjelder.id,
            idType = BrukerIdType.valueOf(sakenGjelder.type.name)
        )
    }

    fun getSak(klagebehandling: CompletedKlagebehandling): Sak {
        return Sak(
            sakstype = Sakstype.FAGSAK,
            fagsaksystem = FagsaksSystem.valueOf(klagebehandling.fagsystem.name),
            fagsakid = klagebehandling.fagsakId
        )
    }

    fun finalizeJournalpost(journalpostId: String, journalfoerendeEnhet: String) {
        val journalpostInSaf = safGraphQlClient.getJournalpostAsSaksbehandler(journalpostId)
            ?: throw Exception("Journalpost with id $journalpostId not found in SAF")

        if (journalpostCanBeFinalized(journalpostInSaf)) {
            logger.debug("Finalizing journalpost $journalpostId in Dokarkiv")
            dokArkivClient.finalizeJournalpost(journalpostId, journalfoerendeEnhet)
        } else {
            //TODO: Sjekk hvor vanlig dette er, og om det heller bør være en warning.
            logger.debug("Journalpost $journalpostId already finalized. Returning.")
        }
    }

    private fun journalpostCanBeFinalized(journalpostInSaf: Journalpost): Boolean {
        return when (journalpostInSaf.journalstatus) {
            Journalstatus.MOTTATT,
            Journalstatus.UNDER_ARBEID,
            Journalstatus.RESERVERT,
            Journalstatus.OPPLASTING_DOKUMENT,
            Journalstatus.UKJENT_BRUKER,
            Journalstatus.FERDIGSTILT -> true

            else -> false
        }
    }

    fun updateAvsenderInJournalpost(
        journalpostId: String,
        avsender: PartId,
    ) {
        val requestInput = getUpdateAvsenderMottakerInJournalpostRequest(avsender)

        dokArkivClient.updateAvsenderMottakerInJournalpost(
            journalpostId = journalpostId,
            input = requestInput,
        )
    }

    private fun getUpdateAvsenderMottakerInJournalpostRequest(avsender: PartId): UpdateAvsenderMottakerInJournalpostRequest {
        val avsenderPart = genericApiService.searchPart(
            searchPartInput = SearchPartInput(identifikator = avsender.id)
        )
        return UpdateAvsenderMottakerInJournalpostRequest(
            avsenderMottaker = AvsenderMottaker(
                id = avsender.id,
                idType = avsender.type.toAvsenderMottakerIdType(),
                navn = avsenderPart.name
            ),
        )
    }

    fun updateSakInJournalpost(
        journalpostId: String,
        tema: Tema,
        bruker: Bruker,
        sak: Sak,
        journalfoerendeEnhet: String
    ) {
        val requestInput = UpdateSakInJournalpostRequest(
            tema = tema,
            bruker = bruker,
            sak = sak,
            journalfoerendeEnhet = journalfoerendeEnhet,
        )

        dokArkivClient.updateSakInJournalpost(
            journalpostId = journalpostId,
            input = requestInput,
        )
    }

    private fun Journalpost.isFinalized(): Boolean {
        return when (journalposttype) {
            Journalposttype.I -> {
                when (journalstatus) {
                    Journalstatus.JOURNALFOERT -> true
                    else -> false
                }
            }

            Journalposttype.U -> {
                when (journalstatus) {
                    Journalstatus.FERDIGSTILT,
                    Journalstatus.EKSPEDERT -> true

                    else -> false
                }
            }

            Journalposttype.N -> {
                when (journalstatus) {
                    Journalstatus.FERDIGSTILT,
                    Journalstatus.EKSPEDERT -> true

                    else -> false
                }
            }
        }
    }

    fun handleJournalpostBasedOnInfotrygdSak(
        journalpostId: String,
        sakId: String,
        avsender: PartId?,
    ): String {
        val journalpostInSaf = safGraphQlClient.getJournalpostAsSaksbehandler(journalpostId)
            ?: throw JournalpostNotFoundException("Fant ikke journalpost i SAF")

        val tema = journalpostInSaf.tema
        val sakFromKlanke = fssProxyClient.getSak(sakId)

        return handleJournalpost(
            journalpostId = journalpostId,
            avsender = avsender,
            tema = Tema.fromNavn(tema.name),
            bruker = Bruker(
                id = sakFromKlanke.fnr, idType = BrukerIdType.FNR
            ),
            sakInFagsystem = Sak(
                sakstype = Sakstype.FAGSAK,
                fagsaksystem = FagsaksSystem.IT01,
                fagsakid = sakFromKlanke.fagsakId
            ),
            journalfoerendeEnhet = kabalInnstillingerClient.getBrukerdata().ansattEnhet.id,
            type = Type.KLAGE,
        )
    }

    fun handleJournalpostBasedOnKabalKlagebehandling(
        journalpostId: String,
        klagebehandlingId: UUID,
        avsender: PartId?,
    ): String {
        val completedKlagebehandling =
            genericApiService.getCompletedKlagebehandling(klagebehandlingId = klagebehandlingId)

        return handleJournalpost(
            journalpostId = journalpostId,
            avsender = avsender,
            tema = Ytelse.of(completedKlagebehandling.ytelseId).toTema(),
            bruker = getBruker(completedKlagebehandling.sakenGjelder),
            sakInFagsystem = getSak(completedKlagebehandling),
            journalfoerendeEnhet = completedKlagebehandling.klageBehandlendeEnhet,
            type = Type.ANKE,
        )
    }

    private fun handleJournalpost(
        journalpostId: String,
        avsender: PartId?,
        tema: Tema,
        bruker: Bruker,
        sakInFagsystem: Sak,
        journalfoerendeEnhet: String,
        type: Type,
    ): String {
        logger.debug("handleJournalpost called")
        val journalpostInSaf = safGraphQlClient.getJournalpostAsSaksbehandler(journalpostId)
            ?: throw Exception("Journalpost with id $journalpostId not found in SAF")

        secureLogger.debug(
            "handleJournalpost called. Fetched journalpostInSaf: {}, sak: {}, tema: {}",
            journalpostInSaf,
            sakInFagsystem,
            tema
        )

        if (journalpostInSaf.journalposttype != Journalposttype.N
            && avsenderMottakerIsMissing(journalpostInSaf.avsenderMottaker)
            && !journalpostInSaf.isFinalized()
            && avsender == null
        ) {
            throw SectionedValidationErrorWithDetailsException(
                title = "Validation error",
                sections = listOf(
                    ValidationSection(
                        section = "saksdata",
                        properties = listOf(
                            InvalidProperty(
                                field = CreateAnkeInputView::avsender.name,
                                reason = "Velg en avsender."
                            )
                        )
                    )
                )
            )
        }

        if (journalpostInSaf.journalposttype != Journalposttype.N && avsender != null) {
            if (journalpostInSaf.avsenderMottaker?.id != avsender.id) {
                logger.debug("updating avsender in journalpost")
                updateAvsenderInJournalpost(
                    journalpostId = journalpostId,
                    avsender = avsender,
                )
            }
        }

        if (journalpostInSaf.isFinalized()) {

            //Never change journalpost for klager
            if (type == Type.KLAGE) {
                return journalpostId
            }

            return if (journalpostAndCompletedKlagebehandlingHaveTheSameFagsak(
                    journalpostInSaf = journalpostInSaf,
                    sakInFagsystem = sakInFagsystem,
                )
            ) {
                logger.debug("journalpostAndCompletedKlagebehandlingHaveTheSameFagsak")
                journalpostId
            } else {
                logger.debug("Creating new createNewJournalpostBasedOnExistingJournalpost")
                secureLogger.debug(
                    "Creating new createNewJournalpostBasedOnExistingJournalpost. JournalpostinSaf: {}, sak: {}",
                    journalpostInSaf,
                    sakInFagsystem
                )
                val newJournalpostId = createNewJournalpostBasedOnExistingJournalpost(
                    oldJournalpost = journalpostInSaf,
                    sak = sakInFagsystem,
                    tema = tema,
                    bruker = bruker,
                    journalfoerendeEnhet = journalfoerendeEnhet,
                )
                dokArkivClient.registerErrorInSaksId(journalpostId)
                newJournalpostId
            }
        } else {
            logger.debug("Journalpost is not finalized")
            secureLogger.debug("Journalpost is not finalized: {}", journalpostInSaf)

            if (type != Type.KLAGE && !journalpostAndCompletedKlagebehandlingHaveTheSameFagsak(
                    journalpostInSaf = journalpostInSaf,
                    sakInFagsystem = sakInFagsystem,
                )
            ) {
                updateSakInJournalpost(
                    journalpostId = journalpostId,
                    tema = tema,
                    bruker = bruker,
                    sak = sakInFagsystem,
                    journalfoerendeEnhet = journalfoerendeEnhet,
                )
            }

            finalizeJournalpost(
                journalpostId = journalpostId,
                journalfoerendeEnhet = journalfoerendeEnhet,
            )
            return journalpostId
        }
    }

    private fun avsenderMottakerIsMissing(avsenderMottaker: no.nav.klage.clients.saf.graphql.AvsenderMottaker?): Boolean {
        return if (avsenderMottaker == null) {
            true
        } else if (avsenderMottaker.type == no.nav.klage.clients.saf.graphql.AvsenderMottaker.AvsenderMottakerIdType.FNR) {
            avsenderMottaker.id == null
        } else avsenderMottaker.navn == null
    }

    private fun createNewJournalpostBasedOnExistingJournalpost(
        oldJournalpost: Journalpost,
        sak: Sak,
        tema: Tema,
        bruker: Bruker,
        journalfoerendeEnhet: String,
    ): String {
        val requestPayload = CreateNewJournalpostBasedOnExistingJournalpostRequest(
            sakstype = Sakstype.FAGSAK,
            fagsakId = sak.fagsakid,
            fagsaksystem = sak.fagsaksystem,
            tema = tema,
            bruker = bruker,
            journalfoerendeEnhet = journalfoerendeEnhet,
        )

        return dokArkivClient.createNewJournalpostBasedOnExistingJournalpost(
            payload = requestPayload,
            oldJournalpostId = oldJournalpost.journalpostId,
            journalfoerendeSaksbehandlerIdent = tokenUtil.getIdent()
        ).nyJournalpostId
    }

    private fun journalpostAndCompletedKlagebehandlingHaveTheSameFagsak(
        journalpostInSaf: Journalpost,
        sakInFagsystem: Sak,
    ): Boolean {
        return if (journalpostInSaf.sak?.fagsakId == null || journalpostInSaf.sak.fagsaksystem == null) {
            false
        } else {
            logger.debug(
                "journalpostInSaf.sak.fagsaksystem: {}, sak.fagsaksystem: {}",
                FagsaksSystem.valueOf(journalpostInSaf.sak.fagsaksystem),
                sakInFagsystem.fagsaksystem
            )
            (journalpostInSaf.sak.fagsakId == sakInFagsystem.fagsakid
                    && FagsaksSystem.valueOf(journalpostInSaf.sak.fagsaksystem) == sakInFagsystem.fagsaksystem)
        }
    }

    fun updateDocumentTitle(
        journalpostId: String,
        dokumentInfoId: String,
        title: String
    ) {
        dokArkivClient.updateDocumentTitle(
            journalpostId = journalpostId,
            input = createUpdateDocumentTitleJournalpostInput(
                dokumentInfoId = dokumentInfoId,
                title = title
            )
        )
    }

    private fun createUpdateDocumentTitleJournalpostInput(
        dokumentInfoId: String,
        title: String
    ): UpdateDocumentTitleJournalpostInput {
        return UpdateDocumentTitleJournalpostInput(
            dokumenter = listOf(
                UpdateDocumentTitleDokumentInput(
                    dokumentInfoId = dokumentInfoId,
                    tittel = title
                )
            )
        )
    }

    private fun PartView.PartType.toAvsenderMottakerIdType(): AvsenderMottakerIdType {
        return when (this) {
            PartView.PartType.FNR -> AvsenderMottakerIdType.FNR
            PartView.PartType.ORGNR -> AvsenderMottakerIdType.ORGNR
        }
    }
}