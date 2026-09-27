# FORJA v27 — corecții cerute după v26

Această actualizare repară harta, elimină confirmarea redundantă de după sincronizare și readuce interfața originală Somn. Nu schimbă organizarea continuă implementată în v26.

## Pagina eliminată

`PermissionHub` nu mai afișează pagina „Contul tău este conectat.” și lista suplimentară de stări. După activarea explicită reușită, salvează prin controllerul existent și închide configurarea. Erorile rămân pe pasul de activare, iar un cont schimbat în timpul operației trebuie reverificat. Introducerea ilustrată, permisiunile și panoul de activare sunt păstrate.

## Somn

Tabul principal afișează din nou ecranul original, cu alarme, sunete și istoric local. Nu mai există o pagină nouă în fața lui. Înregistrarea și sincronizarea păstrează infrastructura existentă. Un adaptor transmite starea reală a înregistrării către cardul original Start/Oprire, fără să inventeze sesiuni în baza locală.

## Hartă

Harta 2D este independentă de inițializarea 3D. Trecerea la vector/3D are loc numai după încărcarea reușită; eșecul nu trebuie să acopere harta funcțională. Resursele schimbate primesc versiune explicită pentru a evita folosirea scripturilor vechi din cache.

Rezultatele verificărilor și identificatorii livrării sunt în `delivery.md` după finalizarea pachetului.
