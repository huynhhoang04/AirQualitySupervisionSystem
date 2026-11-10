import { Navigate, Route, Routes, useParams } from "react-router-dom";

import { ConnectPage } from "./pages/ConnectPage";
import { RoomPage } from "./pages/RoomPage";
import { RoomSettingsPage } from "./pages/RoomSettingsPage";
import { useDevices } from "./hooks/useAqss";

/**
 * The app has one entry decision: is there a machine connected yet?
 *
 * No dashboard to walk into, no empty state explaining itself. A first-time visitor goes straight to
 * the connect flow, and stays there until a node answers.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/connect" element={<ConnectPage />} />
      <Route path="/room/:deviceId" element={<RoomRoute />} />
      <Route path="/room/:deviceId/settings" element={<SettingsRoute />} />
      <Route path="/" element={<Entry />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function Entry() {
  const devices = useDevices();
  if (devices.isLoading) {
    return <div className="grid min-h-screen place-items-center text-sm text-muted">Đang tải…</div>;
  }
  const first = devices.data?.[0];
  return first ? (
    <Navigate to={`/room/${encodeURIComponent(first.device_id)}`} replace />
  ) : (
    <Navigate to="/connect" replace />
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
