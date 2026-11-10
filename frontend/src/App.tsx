import { Navigate, Route, Routes, useParams } from "react-router-dom";

import { ConnectPage } from "./pages/ConnectPage";
import { GuidePage } from "./pages/GuidePage";
import { RoomPage } from "./pages/RoomPage";
import { RoomSettingsPage } from "./pages/RoomSettingsPage";
import { RoomsPage } from "./pages/RoomsPage";

/**
 * Four routes. The home screen lists machines, so a node that lost its room — or never finished
 * pairing — is still visible and still reachable.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<RoomsPage />} />
      <Route path="/connect" element={<ConnectPage />} />
      <Route path="/guide" element={<GuidePage />} />
      <Route path="/room/:deviceId" element={<RoomRoute />} />
      <Route path="/room/:deviceId/settings" element={<SettingsRoute />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function RoomRoute() {
  const { deviceId = "" } = useParams();
  return <RoomPage deviceId={deviceId} />;
}

function SettingsRoute() {
  const { deviceId = "" } = useParams();
  return <RoomSettingsPage deviceId={deviceId} />;
}
