# Tap-Translator (탭 화면 번역기)

Google Gemini Flash와 Google ML Kit OCR을 결합한 실시간 안드로이드 화면 번역기 앱입니다.  
스마트폰 화면 구석에 플로팅 번역 버튼을 띄워두고, 외국어 게임·웹툰·해외 앱 사용 중 버튼을 한 번 터치하면 화면에 보이는 텍스트를 즉석에서 인식하여 매끄럽고 자연스러운 한국어로 번역해 화면 위에 바로 띄워줍니다.

---

## 🌟 주요 기능 및 워크플로우

1. **간편한 번역 및 모델 설정**
   - **원본 화면 언어**: 일본어, 영어, 중국어, 한국어 선택 가능
   - **번역 대상 언어**: 한국어, 영어, 일본어, 중국어 지원
   - **Gemini Flash 모델 연동**: `gemini-1.5-flash` (기본 추천: 초고속 응답 & 넉넉한 무료 할당량), `gemini-2.0-flash`
   - **상황별 번역 톤 조절**:
     - 🎮 **게임/서브컬처**: 게임 대화체, 고유명사, 서브컬처 구어체 최적화
     - 📖 **만화/웹툰**: 감정 표현과 말풍선 대사체에 맞춘 생생한 어투
     - 💼 **일반/표준**: 표준 경어/평어 문맥에 맞춘 정확한 번역
   - **API 키 안전 보관**: Android Keystore 기반 `EncryptedSharedPreferences`로 안전하게 암호화 저장

2. **화면 구석 플로팅 트리거 (Floating Overlay)**
   - 백그라운드 포그라운드 서비스(`FOREGROUND_SERVICE_MEDIA_PROJECTION`)로 동작
   - 화면 가장자리에 자연스럽게 붙는 자석 스냅(Magnetic Snap) 방식의 플로팅 버튼
   - 게임이나 다른 앱을 플레이하는 동안 방해 없이 구석에 상시 대기

3. **즉각적인 온디바이스 OCR + 고성능 AI 번역 (하이브리드 파이프라인)**
   - 플로팅 버튼 탭 시 화면 캡처 (`MediaProjection`)
   - Google ML Kit Text Recognition을 통해 수십 ms 내에 텍스트 블록 및 좌표(`Rect`) 추출
   - 이미지 전체가 아닌 인식된 텍스트 블록만 Gemini Flash로 전송하여 네트워크 대역폭과 레이턴시 극소화
   - 문맥 전체를 파악하여 블록별 자연스러운 번역 JSON 생성

4. **직관적인 번역 결과 표시 (2가지 모드 지원)**
   - **화면 위 오버레이 (Direct Overlay)**: 원문 텍스트 위치(`BoundingBox`) 바로 위에 반투명 배경과 함께 번역 텍스트를 덮어씌워 출력 (텍스트 터치 시 개별 복사 가능)
   - **하단 팝업 카드 (Popup Card)**: 전체 번역 목록을 한눈에 확인할 수 있는 스크롤 카드 및 "전체 복사" 기능 제공
   - 화면 아무 곳이나 탭하면 깔끔하게 번역 오버레이 닫힘

---

## 🛠 기술 스택

- **언어 & 프레임워크**: Kotlin 2.3, Jetpack Compose, Material Design 3
- **OCR 엔진**: Google ML Kit Text Recognition (`text-recognition-japanese`, `text-recognition-korean`, `text-recognition-chinese`, `text-recognition`)
- **AI 번역기**: Google Gemini Flash API (`gemini-1.5-flash` / `gemini-2.0-flash`) via OkHttp & JSON
- **보안**: `androidx.security:security-crypto` (EncryptedSharedPreferences)
- **오버레이 & 화면 캡처**:
  - `WindowManager` (`TYPE_APPLICATION_OVERLAY`)
  - `MediaProjectionManager` & `ImageReader` (GPU 행 패딩 정렬 완벽 보정)
  - Android 14/15 호환 포그라운드 서비스

---

## 🚀 빌드 및 실행 방법

### 사전 요구사항
- Android SDK (API 34+)
- JDK 17 또는 21
- Gemini API Key ([Google AI Studio](https://aistudio.google.com/)에서 무료 발급 가능)

### 빌드 명령어
```bash
# 디버그 APK 빌드
./gradlew assembleDebug

# 빌드된 APK 위치:
# app/build/outputs/apk/debug/app-debug.apk
```

### 테스트 실행
```bash
./gradlew test
```

---

## 📱 사용 방법

1. 앱 실행 후 **Gemini API 키**를 입력하고 [연결 테스트] 버튼을 눌러 정상 작동을 확인합니다.
2. 원하는 **화면 언어(원본)**와 **번역할 언어(대상)**, 번역 톤을 선택합니다.
3. **[번역기 시작]** 버튼을 누릅니다.
   - 최초 실행 시 "다른 앱 위에 그리기 권한" 및 "화면 캡처 권한" 안내 창이 뜨면 승인합니다.
4. 번역하고 싶은 게임이나 앱 화면으로 이동합니다.
5. 화면 가장자리에 위치한 파란색 플로팅 버튼을 터치합니다.
6. 화면 위의 텍스트가 Gemini Flash에 의해 자연스러운 한국어로 즉시 번역되어 나타납니다!
