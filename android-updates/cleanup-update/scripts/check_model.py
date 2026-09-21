"""Held-out Romanian content probes against the exact bundled semantic model.
Requires onnxruntime==1.20.0, tokenizers, numpy. Expects extracted assets in --assets.
Reports raw model evidence; Kotlin policy behavior is covered by JUnit separately.
"""
import argparse,json,pathlib
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer,models,normalizers,pre_tokenizers
p=argparse.ArgumentParser();p.add_argument('--assets',type=pathlib.Path,required=True);a=p.parse_args()
vocab={v:i for i,v in enumerate((a.assets/'vocab.txt').read_text().splitlines())}
t=Tokenizer(models.WordPiece(vocab,unk_token='[UNK]'));t.normalizer=normalizers.BertNormalizer(lowercase=False,strip_accents=False);t.pre_tokenizer=pre_tokenizers.BertPreTokenizer()
options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
s=ort.InferenceSession(str(a.assets/'semantic.onnx'),options,providers=['CPUExecutionProvider'])
topics=json.loads((a.assets/'topics.json').read_text())
cases=[
('preschool','Educație/Preșcolar','Proiect didactic pentru grupa mare de la grădiniță. Copiii recunosc culorile toamnei, sortează frunze și povestesc după imagini. Obiective operaționale: să numească trei fructe și să participe la jocul didactic. Evaluarea se face prin observarea activității copiilor.'),
('invoice','Financiar/Facturi','Factura fiscală numărul 902. Furnizor SC Lumina SRL, cod fiscal RO123456. Beneficiar Asociația Prietenii Școlii. Produse: hârtie copiator, 20 topuri. Valoare fără TVA 400 lei, TVA 76 lei, total de plată 476 lei. Scadența plății: 25 septembrie. Cont IBAN RO49AAAA123456789.'),
('recipe','Bucătărie/Rețete','Ingrediente pentru prăjitura cu mere: 4 mere, 200 g făină, două ouă, zahăr, unt și scorțișoară. Mod de preparare: curăță merele, amestecă ouăle cu zahărul, adaugă făina și untul. Toarnă compoziția în tavă și coace în cuptor la 180 de grade timp de 35 de minute.'),
('cv','Muncă/CV','Curriculum vitae. Experiență profesională: contabil senior la firma Armonia între 2020 și 2025. Responsabilități: întocmirea bilanțurilor și gestiunea bugetelor. Studii: Facultatea de Științe Economice. Competențe: Excel avansat, contabilitate financiară, limba engleză. Certificări profesionale și referințe disponibile la cerere.'),
('contract','Acte/Contracte','Contract de prestări servicii încheiat între Prestator și Beneficiar. Obiectul prezentului contract este realizarea lucrărilor de întreținere. Părțile își asumă obligațiile prevăzute în clauzele următoare. Prețul, durata, condițiile de încetare și răspunderea contractuală se stabilesc de comun acord. Semnăturile părților.'),
('reservation','Călătorii/Rezervări','Confirmarea rezervării la hotelul Aurora. Sosire pe 12 octombrie, plecare pe 15 octombrie. Cameră dublă pentru doi adulți, mic dejun inclus. Număr de rezervare HZ43822. Check-in după ora 14:00, check-out până la ora 11:00. Adresa hotelului și instrucțiuni de acces.'),
('receipt','Financiar/Bonuri','Bon fiscal. Magazin alimentar. Lapte 8,50 lei, pâine 5,00 lei, mere 6,40 lei. Total 19,90 lei. Numerar 20,00 lei. Rest 0,10 lei. Casa de marcat 2. TVA inclus. Vă mulțumim pentru cumpărături!'),
('casual',None,'Salut, ne vedem mâine după-amiază în parc? Eu ajung pe la cinci și aduc și umbrela dacă plouă. Spune-mi când pleci de acasă, ca să ne întâlnim lângă intrare.'),
]
report=[]
for name,expected,text in cases:
 ids=np.array([[101]+t.encode(text).ids[:126]+[102]],dtype=np.int64)
 v=s.run(None,{'input_ids':ids,'attention_mask':np.ones_like(ids)})[0][0];v=v/max(np.linalg.norm(v),1e-12)
 scores=sorted([(x['category'],float(np.max(np.asarray(x['prototypes'])@v))) for x in topics],key=lambda x:-x[1])
 category=scores[0][0] if scores[0][1]>=.22 and scores[0][1]-scores[1][1]>=.06 else None
 report.append({'case':name,'expected':expected,'actual':category,'top':scores[:2]})
 print(json.dumps(report[-1],ensure_ascii=False),flush=True)
(a.assets.parent/'model-probe-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
assert all(r['actual'] in (None,r['expected']) for r in report),'Incorrect confident model prediction'
assert sum(r['actual'] is not None for r in report)>=6,'Too many abstentions'
