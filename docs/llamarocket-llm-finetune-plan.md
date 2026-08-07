# LlamaRocket – Roketçilik Odaklı LLM Fine-Tune Planı

## 1) Stratejik Karar

- **Yol:** From-scratch pretraining yerine **Qwen2.5 tabanlı QLoRA fine-tune + DPO**
- **Gerekçe:** Mevcut donanım (dual P106-100, 6GB VRAM) ile sıfırdan model eğitimi maliyetli ve yavaş
- **Hedef:** LLM’i tahmin eden bileşen olmaktan çıkarıp, deterministik sistemin kararlarını doğru şekilde açıklayan ve tool-use yöneten bir katmana dönüştürmek

## 2) Mevcut Durum (Motor Seçimi)

- Deterministik aday havuzu aktif (fit + TWR + impulse ratio filtreleri)
- Host tarafı simülasyon en iyi adayı seçiyor
- `assign_motor` aynı motoru yeniden atamayı engelliyor
- **Açık risk:** Farklı motorlar arasında sistemsel oscillation-lock eksikliği ve uçtan uca convergence testinin repo’da net olmaması

## 3) Ürün Hedefi

- `create_basic_rocket` + `assign_motor` akışlarında doğru tool-call sırası ve parametre üretimi
- Deterministik motor kararını doğru teknik gerekçeyle kullanıcıya anlatma
- Türkçe + İngilizce sohbet kalitesi
- Ollama üzerinden LlamaRocket provider akışına takılabilirlik

## 4) Base Model Seçimi

- **Ana aday:** `Qwen2.5-1.5B-Instruct`
- **Alternatif:** `Qwen2.5-3B-Instruct` (VRAM izin verirse)
- **Yöntem:** 4-bit QLoRA

## 5) Veri Planı

### 5.1 Sentetik Distillation (Ana Veri)

- Girdi: hedef apogee + roket spec kombinasyonları
- Çıktı: deterministik sistemin seçtiği motor + gerekçe
- Etiketleme: otomatik/scriptli üretim

### 5.2 Tool-Use Verisi

- `create_basic_rocket` ve `assign_motor` için doğru çağrı sırası
- Parametre doğruluğu ve çağrı bağlamı

### 5.3 Domain İçeriği

- OpenRocket/LlamaRocket dokümanları ve simülasyon kavramları
- Ayrı domain pretraining aşaması olmadan SFT karışımına dahil

### 5.4 Veri Hijyeni

- Dedup
- Lisans filtreleme
- Kalite puanlama
- Türkçe/İngilizce oran dengeleme

## 6) Eğitim Akışı

1. **SFT (QLoRA):** Domain dili + tool-use format davranışı
2. **DPO:** Doğru gerekçe (chosen) vs halüsinasyon/tutarsız gerekçe (rejected)
3. **Not:** Ayrı pretraining aşaması plan dışı

## 7) Operasyon ve İzleme

- Tek P106-100 ile SFT/DPO; ikinci kart paralel eval/deneme
- Checkpoint + resume + failure recovery zorunlu
- Takip metrikleri:
  - loss
  - token throughput
  - GPU utilization
  - grad norm
  - validation trend
- Her run’da veri sürümü + config hash kaydı

## 8) Değerlendirme Kriterleri

- Motor seçimi doğruluğu (deterministik ground truth ile otomatik skor)
- Gerekçe doğruluğu (doğru sebeple doğru sonuç)
- Tool-call sırası doğruluğu
- Simülasyon yorumu tutarlılığı
- Türkçe/İngilizce çıktı kalitesi
- Gerçek kullanım senaryolarında uçtan uca davranış

## 9) Riskler ve Ön Koşullar

- Sentetik veri kalitesi deterministik sistem kalitesine bağlı
- Fine-tune öncesi **uçtan uca convergence testi** eklenmeli
- Lisans ve veri kaynağı uyumu için ayrı onay kapısı gerekli
- Kalite düşüşünde SFT/DPO veri karışım oranları revize edilmeli

## 10) Yürütme Planı

### Hafta 1

- Veri üretim scripti
- ~200–300 örnekle pilot SFT
- Pipeline doğrulama

### Hafta 2

- DPO chosen/rejected çiftleri üretimi
- Eval seti kurulumu

### Hafta 3

- Veri hacmini artırma
- Tam SFT + DPO koşuları

### Final

- GGUF quantization
- Ollama paketleme
- LlamaRocket provider listesine ekleme
- Mevcut Qwen3-8B (ONGON) ile latency/kalite A/B karşılaştırması
