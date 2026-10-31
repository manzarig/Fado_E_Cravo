# Speech scripts (pt-PT)

Spoken-word scripts for the three fictional 1940s Portuguese stations of the mod. Each
`<station_id>/<clip>.txt` is fed straight to Piper TTS (`pt_PT-tugão-medium`, one male voice) and then
processed to sound like AM radio. The clip ids are fixed by the audio contract in `CLAUDE.md`; music
clips are not scripted here.

All stations, brands, businesses and people in these scripts are fictional. Real place names
(Lisbon, Peniche, Sesimbra, the Tagus and so on) are used only as setting. No real politicians or
public figures are named.

## Clips

Seconds are estimates at roughly 2.3 words per second; the real duration comes from the generated OGG.
`market_prices_1` has kind `notice` because the station-file schema has no separate market kind.

| Station | Clip | Kind | Approx. seconds | Content (English gloss) |
|---|---|---|---|---|
| emissora_lusitana (670 kc) | ident_1 | ident | ~13 | Station ident: Emissora Lusitana, Lisbon, 670 kc, welcome to listeners, at the service of all Portuguese. |
| emissora_lusitana (670 kc) | ident_2 | ident | ~15 | Second ident: 'Attention' call, speaks to Portugal and the adjacent islands, music, news and the exact time. |
| emissora_lusitana (670 kc) | news_morning_1 | news | ~39 | Five o'clock (dawn) morning bulletin: Government reaffirms neutrality, brief vague foreign news, Lisbon weather, fishermen, shops and buses. |
| emissora_lusitana (670 kc) | news_evening_1 | news | ~38 | Six o'clock (dusk) evening news: neutrality policy spared the country, war abroad in brief, Lisbon welcomes refugees in transit, Mondego works, weather. |
| emissora_lusitana (670 kc) | news_evening_2 | news | ~37 | Second evening bulletin: new neighbourhood market inaugurated in Lisbon with a new bus line, troop movements abroad, Portugal at peace, a comedy at the National Theatre. |
| emissora_lusitana (670 kc) | notice_1 | notice | ~33 | Ministry of Economy rationing notice: sugar, rice and pasta only against ration coupons, handed out at parish councils. |
| emissora_lusitana (670 kc) | notice_2 | notice | ~32 | Harbour authority notice to the sardine fleet: may sail at dawn tomorrow, limited to five miles because of drifting mines, fly the national flag. |
| emissora_lusitana (670 kc) | market_prices_1 | notice | ~33 | Official fixed market prices at the Ribeira market: salt cod, sardines, olive oil, bread, rice, potatoes, eggs; report overcharging. |
| emissora_lusitana (670 kc) | signoff_1 | signoff | ~20 | Night sign-off at eight: thanks listeners, wishes good night, back on air at five, 'may God keep you'. |
| emissora_lusitana (670 kc) | breaking_1 | breaking | ~26 | Breaking: a neutral-flag merchant ship carrying refugees has entered the Tagus bar; Red Cross and port authorities prepare a reception at the Alcantara quay. |
| radio_tejo (1035 kc) | ident_1 | ident | ~14 | Station ident: Radio Tejo, the cheerful voice of the city, 1035 kc, fado and good cheer from Lisbon. |
| radio_tejo (1035 kc) | ident_2 | ident | ~14 | Second ident: 'Attention, friends', you are tuned to Radio Tejo, the wireless that brightens home, street and neighbourhood. |
| radio_tejo (1035 kc) | ad_1 | ad | ~25 | Ad for fictional Sabao Alvorada laundry soap, made in Setubal with pure olive oil, one escudo fifty a bar. |
| radio_tejo (1035 kc) | ad_2 | ad | ~25 | Ad for fictional Sardinhas Barca Nova, tinned in olive oil the day they are caught off Peniche, two escudos a tin. |
| radio_tejo (1035 kc) | ad_3 | ad | ~26 | Ad for fictional Cafe Primavera on Rua Augusta: fresh espresso at fifty centavos, custard tarts hot from the oven. |
| radio_tejo (1035 kc) | ad_4 | ad | ~22 | Ad for fictional Chapelaria Aurora, a hat shop in the Chiado: new felt hats and caps, from fifteen escudos. |
| radio_tejo (1035 kc) | announce_1 | announce | ~17 | Announcer intro to a fado: the Portuguese guitar weeps softly, listen calmly, fado has no hurry. |
| radio_tejo (1035 kc) | announce_2 | announce | ~20 | Announcer intro to a lively Lisbon march: street-party mood, leave the pots and go dance. |
| radio_tejo (1035 kc) | announce_3 | announce | ~22 | Announcer intro to a guitarrada, plus an invitation to write in with requests (fictional address on Rua do Alecrim). |
| radio_tejo (1035 kc) | request_1 | request | ~29 | Listener dedication: Dona Rosalina from Mouraria asks for a fado for husband Armindo's silver wedding anniversary. |
| radio_tejo (1035 kc) | breaking_1 | breaking | ~26 | Breaking: harbour master warns of a strong storm heading for the Tagus estuary; fishing boats must return to port at once. |
| radio_atlantico (1440 kc) | ident_1 | ident | ~13 | Foreign-service ident: Radio Atlantico, Portuguese-language broadcast to Portugal, 1440 kc, 'turn the volume down and listen carefully'. |
| radio_atlantico (1440 kc) | news_1 | news | ~34 | War overview: fighting on several fronts is more serious than official stations admit; slow offensive in the east, desert back and forth, air raids, rescue crews at work. |
| radio_atlantico (1440 kc) | news_2 | news | ~34 | Sea war: escorted merchant convoys, some arrive unscathed and others do not, submarines on the northern routes, slow and dangerous Mediterranean. |
| radio_atlantico (1440 kc) | news_3 | news | ~35 | Late bulletin: delegates meet to discuss postwar Europe, no end in sight, hardship in occupied cities, families listen to hidden radios, 'hope is not rationed'. |
| radio_atlantico (1440 kc) | personal_messages_1 | personal_messages | ~37 | Coded 'personal messages', each repeated: the grandmother got the oranges; the carnation is in the vase; the seven o'clock train; gulls sleeping in Cascais; the key under the third stone. Never explained. |
| radio_atlantico (1440 kc) | breaking_1 | breaking | ~26 | Urgent bulletin: a major naval engagement reported in the Atlantic, fleets on both sides at sea, no reliable figures yet; stay tuned on 1440 kc. |

## Style notes

- European Portuguese, post-1990 orthography, pt-PT vocabulary (`autocarro`, `comboio`, `telefonia`,
  `quilociclos`, `a transmitir` instead of the Brazilian gerund).
- Plain text for TTS: no digits, abbreviations, brackets, stage directions or speaker names. Numbers,
  times, prices and frequencies are written as words ("seiscentos e setenta quilociclos", "dezoito
  horas", "dez escudos e cinquenta centavos").
- Short sentences with commas and full stops to pace the voice. A colon appears only in the repeated
  "Repito:" lines of the coded messages.
- Emissora Lusitana: formal, sober, official. It reaffirms neutrality and reports the war briefly and
  vaguely. Its breaking news is a neutral refugee ship arriving in Lisbon.
- Rádio Tejo: warm and popular, with ads for invented brands only (Sabão Alvorada, Sardinhas Barca Nova,
  Café Primavera, Chapelaria Aurora). Song intros use "E agora, para os nossos ouvintes, um fado" and
  never name real songs or singers. The dedication uses fictional names.
- Rádio Atlântico: direct foreign-service voice for a weak night signal. War news stays vague, with no
  named countries or people and no gore. `personal_messages_1` is a series of cryptic coded messages,
  each repeated, and is never explained. "O cravo está no vaso" is a deliberate seed for the future
  Proto-Cravo storyline.
- Idents `ident_1` and `ident_2` differ in wording and structure within each station that has two.
- Clips that may play at any time of day never announce the current time. Only the morning bulletin,
  the evening bulletin and the sign-off do (eight, twenty-one and twenty-three hours), because it is
  part of their format. Opening or serving hours inside an item (shops, ration-coupon desks, a cafe) are
  fine.
