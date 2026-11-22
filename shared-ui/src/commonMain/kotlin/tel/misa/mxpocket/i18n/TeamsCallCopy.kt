package tel.misa.mxpocket.i18n

/** Keep the short LCD captions distinct from the evidence and recovery instructions in setup. */
internal object TeamsCallCopy {
    val NoKeypad = Copy("No keypad", "Sans clavier", "Nema tastature")
    val ConnectUsb = Copy("Connect USB", "Brancher en USB", "Poveži USB")
    val PluginOff = Copy("Plugin off", "Module inactif", "Dodatak isključen")
    val Plugins = Copy("Plugins", "Modules", "Dodaci")
    val Android14 = Copy("Android 14+", "Android 14+", "Android 14+")
    val Required = Copy("Required", "Requis", "Neophodno")
    val CallNotice = Copy("Call notice", "Avis d’appel", "Obaveštenje o pozivu")
    val ThisProfile = Copy("This profile", "Ce profil", "Ovaj profil")
    val NoTeams = Copy("No Teams", "Teams absent", "Nema Teams-a")
    val Locked = Copy("Locked", "Verrouillé", "Zaključano")
    val UnlockPhone = Copy("Unlock phone", "Déverrouiller", "Otključaj telefon")
    val StatusOff = Copy("Status off", "Suivi inactif", "Praćenje isključeno")
    val NotReady = Copy("Not ready", "Pas prêt", "Nije spremno")
    val NotConnected = Copy("Not connected", "Non connecté", "Nije povezano")
    val Unknown = Copy("Unknown", "Inconnu", "Nepoznato")
    val TypeUnknown = Copy("Type unknown", "Type inconnu", "Nepoznat tip")
    val Incoming = Copy("Incoming", "Appel entrant", "Dolazni poziv")
    val Screening = Copy("Screening", "Filtrage", "Provera poziva")
    val NoSignal = Copy("No signal", "Aucun signal", "Nema signala")
    val CheckFilters = Copy("Check filters", "Vérifier les filtres", "Proveri filtere")
    val ShowControls = Copy("Show controls", "Voir les commandes", "Prikaži kontrole")

    val NoKeypadDetail = Copy("Connect and authorize the wired keypad. A virtual keypad cannot observe calls.",
        "Branchez le clavier filaire et autorisez son utilisation. Un clavier virtuel ne peut pas suivre les appels.",
        "Poveži žičnu tastaturu i odobri pristup. Virtuelna tastatura ne može da prati pozive.")
    val DisabledDetail = Copy("Enable the Teams plugin to use call status.",
        "Activez le module Teams pour consulter l’état des appels.",
        "Uključi dodatak Teams za prikaz stanja poziva.")
    val UnsupportedOsDetail = Copy("Call status needs Android 14 or later.",
        "L’état des appels nécessite Android 14 ou une version ultérieure.",
        "Za prikaz stanja poziva potreban je Android 14 ili noviji.")
    val ProfileNoticeDetail = Copy("Teams posted an ongoing call notification in this Android profile. With multiple profiles, MX Pocket cannot verify the call or operate the mic.",
        "Teams a publié une notification d’appel en cours dans ce profil Android. En présence de plusieurs profils, MX Pocket ne peut ni confirmer l’appel ni commander le micro.",
        "Teams je objavio obaveštenje o pozivu u toku na ovom Android profilu. Kada postoji više profila, MX Pocket ne može da potvrdi poziv niti da upravlja mikrofonom.")
    val NotInstalledDetail = Copy("No launchable Teams app was found in this Android profile.",
        "Aucune appli Teams pouvant être ouverte n’a été trouvée dans ce profil Android.",
        "Na ovom Android profilu nije pronađena aplikacija Teams koja se može pokrenuti.")
    val LockedDetail = Copy("Unlock the phone to resume call observation.",
        "Déverrouillez le téléphone pour reprendre le suivi des appels.",
        "Otključaj telefon za nastavak praćenja poziva.")
    val OffDetail = Copy("Teams call status is off. No calls are being observed.",
        "Le suivi des appels Teams est désactivé. Aucun appel n’est suivi.",
        "Praćenje stanja Teams poziva je isključeno. Nijedan poziv se ne prati.")
    val NeedsAccessDetail = Copy("Notification access for MX Pocket Teams calls is off. Audio and Navigation access do not enable Teams.",
        "L’accès aux notifications est désactivé pour les appels Teams de MX Pocket. Les autorisations Audio et Navigation n’activent pas Teams.",
        "Pristup obaveštenjima je isključen za Teams pozive u aplikaciji MX Pocket. Dozvole za Audio i Navigaciju ne omogućavaju pristup za Teams.")
    val DisconnectedDetail = Copy("Teams notification access is on, but the listener is not connected. Check the service in Android settings.",
        "L’accès aux notifications Teams est activé, mais le service d’écoute n’est pas connecté. Vérifiez le service dans les paramètres Android.",
        "Pristup Teams obaveštenjima je uključen, ali servis za obaveštenja nije povezan. Proveri servis u Android podešavanjima.")
    val ToolbarOffDetail = Copy("Teams posted an ongoing call notification. The Teams accessibility service is off; it is optional for read-only status.",
        "Teams a publié une notification d’appel en cours. Le service d’accessibilité Teams est désactivé ; il est facultatif pour la simple consultation de l’état.",
        "Teams je objavio obaveštenje o pozivu u toku. Servis pristupačnosti za Teams je isključen; nije obavezan ako samo pratiš stanje poziva.")
    val ToolbarDisconnectedDetail = Copy("Teams posted an ongoing call notification. Accessibility access is on, but its service is not connected.",
        "Teams a publié une notification d’appel en cours. L’accès aux fonctions d’accessibilité est activé, mais le service n’est pas connecté.",
        "Teams je objavio obaveštenje o pozivu u toku. Pristupačnost je omogućena, ali njen servis nije povezan.")
    val UnavailableDetail = Copy("No unique call signal is available. Check the call in Teams.",
        "Aucun signal ne permet d’identifier un appel unique. Vérifiez l’appel dans Teams.",
        "Nema signala koji jednoznačno određuje poziv. Proveri poziv u aplikaciji Teams.")
    val UntypedDetail = Copy("Teams posted a call notification without a supported call type. Check the call in Teams.",
        "Teams a publié une notification d’appel sans type d’appel pris en charge. Vérifiez l’appel dans Teams.",
        "Teams je objavio obaveštenje o pozivu bez podržanog tipa poziva. Proveri poziv u aplikaciji Teams.")
    val IncomingDetail = Copy("Teams reports an incoming call, not a joined call.",
        "Teams signale un appel entrant, pas un appel rejoint.",
        "Teams prijavljuje dolazni poziv, a ne poziv kojem si se pridružio.")
    val ScreeningDetail = Copy("Teams reports call screening, not a joined call.",
        "Teams signale un filtrage d’appel, pas un appel rejoint.",
        "Teams prijavljuje proveru poziva, a ne poziv kojem si se pridružio.")
    val NoSignalDetail = Copy("No eligible ongoing Teams call notification is visible. This does not mean there is no call.",
        "Aucune notification Teams d’appel en cours ne remplit les critères de détection. Cela ne signifie pas qu’il n’y a pas d’appel.",
        "Nije vidljivo nijedno odgovarajuće Teams obaveštenje o pozivu u toku. To ne znači da poziva nema.")
    val NotificationDetail = Copy("Teams posted an ongoing call notification. Open the meeting on this phone and show its controls to confirm the call.",
        "Teams a publié une notification d’appel en cours. Ouvrez la réunion sur ce téléphone et affichez ses commandes pour confirmer l’appel.",
        "Teams je objavio obaveštenje o pozivu u toku. Otvori sastanak na ovom telefonu i prikaži njegove kontrole da bi se poziv potvrdio.")
    val JoinedDetail = Copy("A local Teams call was confirmed on this phone.",
        "Un appel Teams local a été confirmé sur ce téléphone.",
        "Na ovom telefonu je potvrđen lokalni Teams poziv.")
}
